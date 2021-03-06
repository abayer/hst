/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.io.compress.snappy;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.compress.Decompressor;

/**
 * A {@link Decompressor} based on the snappy compression algorithm.
 * http://code.google.com/p/snappy/
 */
public class SnappyDecompressor implements Decompressor {
  private static final Log LOG = 
    LogFactory.getLog(SnappyCompressor.class.getName());
  private static final int DEFAULT_DIRECT_BUFFER_SIZE = 64 * 1024;
  
  // HACK - Use this as a global lock in the JNI layer
  @SuppressWarnings({ "unchecked", "unused" })
  private static Class clazz = SnappyDecompressor.class;

  private int directBufferSize;
  private Buffer compressedDirectBuf = null;
  private int compressedDirectBufLen;
  private Buffer uncompressedDirectBuf = null;
  private byte[] userBuf = null;
  private int userBufOff = 0, userBufLen = 0;
  private boolean finished;

  private static boolean nativeSnappyLoaded = false;

  static {
    if (SnappyNativeCodeLoader.isNativeCodeLoaded()) {
      // Initialize the native library
      try {
        initIDs();
        nativeSnappyLoaded = true;
      } catch (Throwable t) {
        // Ignore failure to load/initialize snappy
        LOG.warn(t.toString());
        nativeSnappyLoaded = false;
      }
    } else {
      LOG.error("Cannot load " + SnappyDecompressor.class.getName() + 
      " without snappy library!");
      nativeSnappyLoaded = false;
    }
  }
  
  /**
   * Are the snappy decompressors initialized? 
   * 
   * @return true if initialized, otherwise false
   */
  public static boolean isNativeSnappyLoaded() {
    return nativeSnappyLoaded;
  }
  
  /**
   * Creates a new compressor.
   * 
   * @param directBufferSize size of the direct buffer to be used.
   */
  public SnappyDecompressor(int directBufferSize) {
    this.directBufferSize = directBufferSize;

    compressedDirectBuf = ByteBuffer.allocateDirect(directBufferSize);
    uncompressedDirectBuf = ByteBuffer.allocateDirect(directBufferSize);
    uncompressedDirectBuf.position(directBufferSize);

  }
  
  /**
   * Creates a new decompressor with the default buffer size.
   */
  public SnappyDecompressor() {
    this(DEFAULT_DIRECT_BUFFER_SIZE);
  }

  public synchronized void setInput(byte[] b, int off, int len) {
    if (b == null) {
      throw new NullPointerException();
    }
    if (off < 0 || len < 0 || off > b.length - len) {
      throw new ArrayIndexOutOfBoundsException();
    }

    this.userBuf = b;
    this.userBufOff = off;
    this.userBufLen = len;

    setInputFromSavedData();

    // Reinitialize snappy's output direct-buffer
    uncompressedDirectBuf.limit(directBufferSize);
    uncompressedDirectBuf.position(directBufferSize);
  }
  
  /**
   * If a write would exceed the capacity of the direct buffers, it is set
   * aside to be loaded by this function while the compressed data are
   * consumed.
   */
  synchronized void setInputFromSavedData() {
    compressedDirectBufLen = Math.min(userBufLen, directBufferSize);

    // Reinitialize snappy's input direct buffer
    compressedDirectBuf.rewind();
    ((ByteBuffer) compressedDirectBuf).put(userBuf, userBufOff,
        compressedDirectBufLen);

    // Note how much data is being fed to snappy
    userBufOff += compressedDirectBufLen;
    userBufLen -= compressedDirectBufLen;
  }

  public synchronized void setDictionary(byte[] b, int off, int len) {
    // do nothing
  }

  public synchronized boolean needsInput() {
    // Consume remaining compressed data?
    if (uncompressedDirectBuf.remaining() > 0) {
      return false;
    }

    // Check if snappy has consumed all input
    if (compressedDirectBufLen <= 0) {
      // Check if we have consumed all user-input
      if (userBufLen <= 0) {
        return true;
      } else {
        setInputFromSavedData();
      }
    }

    return false;
  }

  public synchronized boolean needsDictionary() {
    return false;
  }

  public synchronized boolean finished() {
    return (finished && uncompressedDirectBuf.remaining() == 0);
  }

  public synchronized int decompress(byte[] b, int off, int len)
      throws IOException {
    if (b == null) {
      throw new NullPointerException();
    }
    if (off < 0 || len < 0 || off > b.length - len) {
      throw new ArrayIndexOutOfBoundsException();
    }

    int n = 0;

    // Check if there is uncompressed data
    n = uncompressedDirectBuf.remaining();
    if (n > 0) {
      n = Math.min(n, len);
      ((ByteBuffer) uncompressedDirectBuf).get(b, off, n);
      return n;
    }
    if (compressedDirectBufLen > 0) {
      // Re-initialize the snappy's output direct buffer
      uncompressedDirectBuf.rewind();
      uncompressedDirectBuf.limit(directBufferSize);

      // Decompress data
      n = decompressBytesDirect();
      uncompressedDirectBuf.limit(n);
      
      if (userBufLen <= 0) {
        finished = true;
      }
      
      // Get atmost 'len' bytes
      n = Math.min(n, len);
      ((ByteBuffer) uncompressedDirectBuf).get(b, off, n);
    }
    
    return n;
  }

  public synchronized void reset() {
    finished = false;
    compressedDirectBufLen = 0;
    uncompressedDirectBuf.limit(directBufferSize);
    uncompressedDirectBuf.position(directBufferSize);
    userBufOff = userBufLen = 0;
  }

  public synchronized void end() {
    // do nothing
  }

  protected void finalize() {
    end();
  }

  private native static void initIDs();

  private native int decompressBytesDirect();
}
