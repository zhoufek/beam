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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class FileDownloaderTest {
  @Rule public TestName testName = new TestName();

  @Test
  public void testDownloadCopiesFile() throws IOException {
    Path input = Paths.get(Resources.getResource("download-file/small-file.txt").getPath());
    Path output = createDownloadWritePath(testName.getMethodName());

    FileDownloader.download(input.toAbsolutePath().toString(), output);

    assertFilesHaveSameContent(input, output);
  }

  @Test
  public void testDownloadHandlesLargeFiles() throws IOException {
    Path input = Paths.get(Resources.getResource("download-file/large-file.txt").getPath());
    Path output = createDownloadWritePath(testName.getMethodName());

    FileDownloader.download(input.toAbsolutePath().toString(), output);

    assertFilesHaveSameContent(input, output);
  }

  @Test
  public void testDownloadFailsWithNonExistentExternalFile() throws IOException {
    String badPath = "some-bad-file-name-please-don't-create";
    Path output = createDownloadWritePath(testName.getMethodName());

    assertThrows(IOException.class, () -> FileDownloader.download(badPath, output));
  }

  /**
   * Returns a temporary file specifically for the test.
   *
   * <p>This is unique across calls with the same {@code testMethodName}.
   */
  private static Path createDownloadWritePath(String testMethodName) throws IOException {
    return Files.createTempFile(testMethodName, UUID.randomUUID().toString());
  }

  /**
   * Asserts that the content of {@code actual} and {@code expected} are equal.
   *
   * <p>If either file does not exist, then this will fail the test.
   */
  private static void assertFilesHaveSameContent(Path actual, Path expected) {
    assertEquals(readFileContentOrFail(actual), readFileContentOrFail(expected));
  }

  /** Returns the content of {@code filePath} or fails the test if it doesn't exist. */
  private static String readFileContentOrFail(Path filePath) {
    try {
      return new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
    } catch (IOException e) {
      fail(String.format("Could not read file %s: %s", filePath.toAbsolutePath(), e));
      return "";
    }
  }
}
