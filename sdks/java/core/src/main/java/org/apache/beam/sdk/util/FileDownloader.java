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
package org.apache.beam.sdk.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility for downloading files. */
@Internal
public final class FileDownloader {
  private static final Logger LOG = LoggerFactory.getLogger(FileDownloader.class);

  private static final int DEFAULT_BUFFER_SIZE = 1024;

  private FileDownloader() {}

  /**
   * Downloads a file.
   *
   * @param externalPath the full path of the external resource, such as a Google Cloud Storage
   *     object. This can be a local file, in which case, this method acts more as a copy than as a
   *     download.
   * @param localPath the {@link Path} for the file to download to.
   * @throws IOException if {@code externalPath} cannot be read from or if {@code localPath} cannot
   *     be written to.
   */
  public static void download(String externalPath, Path localPath) throws IOException {
    Path absolutePath = localPath.toAbsolutePath();
    LOG.info("Downloading {} to {}", externalPath, absolutePath);

    ResourceId externalResource = FileSystems.match(externalPath).metadata().get(0).resourceId();
    try (ReadableByteChannel readChannel = FileSystems.open(externalResource)) {
      try (OutputStream writeStream = Files.newOutputStream(absolutePath)) {
        try (WritableByteChannel writeChannel = Channels.newChannel(writeStream)) {
          ByteBuffer buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
          while (readChannel.read(buffer) != -1) {
            buffer.flip();
            writeChannel.write(buffer);
            buffer.compact();
          }
        }
      }
    }

    LOG.info("Successfully downloaded {} to {}", externalPath, absolutePath);
  }
}
