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
package org.apache.beam.sdk.io.kafka;

import static org.apache.beam.sdk.io.kafka.KafkaIOUtils.KAFKA_GCS_TRUST_STORE_CONSUMER_FACTORY_FN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.apache.beam.sdk.util.FileDownloader;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

/** Tests of {@link KafkaIOUtils}. */
@RunWith(JUnit4.class)
public class KafkaIOUtilsTest {

  @Test
  public void testOffsetConsumerConfigOverrides() throws Exception {
    KafkaIO.Read<?, ?> spec =
        KafkaIO.read()
            .withBootstrapServers("broker_1:9092,broker_2:9092")
            .withTopic("my_topic")
            .withOffsetConsumerConfigOverrides(null);
    Map<String, Object> offsetConfig =
        KafkaIOUtils.getOffsetConsumerConfig(
            "name", spec.getOffsetConsumerConfig(), spec.getConsumerConfig());
    assertTrue(
        offsetConfig
            .get(ConsumerConfig.GROUP_ID_CONFIG)
            .toString()
            .matches("name_offset_consumer_\\d+_none"));

    assertEquals(false, offsetConfig.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
    assertEquals("read_uncommitted", offsetConfig.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG));

    String offsetGroupId = "group.offsetConsumer";
    KafkaIO.Read<?, ?> spec2 =
        KafkaIO.read()
            .withBootstrapServers("broker_1:9092,broker_2:9092")
            .withTopic("my_topic")
            .withOffsetConsumerConfigOverrides(
                ImmutableMap.of(ConsumerConfig.GROUP_ID_CONFIG, offsetGroupId));
    offsetConfig =
        KafkaIOUtils.getOffsetConsumerConfig(
            "name2", spec2.getOffsetConsumerConfig(), spec2.getConsumerConfig());
    assertEquals(offsetGroupId, offsetConfig.get(ConsumerConfig.GROUP_ID_CONFIG));
    assertEquals(false, offsetConfig.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
    assertEquals("read_uncommitted", offsetConfig.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG));
  }

  @Test
  public void testConsumerGcsFactoryOverridesGcsFilePaths() throws IOException {
    String gcsPath = "gs://path/to/gcs/file";
    Map<String, Object> originalMap = ImmutableMap.of("someValue", gcsPath);
    Path targetPath = Paths.get("");

    try (MockedStatic<Files> createTempMock = mockStatic(Files.class)) {
      createTempMock.when(() -> Files.createTempFile(any(), any())).thenReturn(targetPath);
      try (MockedStatic<FileDownloader> downloaderMock = mockStatic(FileDownloader.class)) {
        downloaderMock.when(() -> FileDownloader.download(any(), any())).thenAnswer(invocation -> null);
        try (MockedStatic<KafkaConsumer> consumerMock = mockStatic(KafkaConsumer.class)) {
          consumerMock.when(KafkaConsumer::new).thenReturn(new MockConsumer<byte[], byte[]>(
              OffsetResetStrategy.NONE));

          try (Consumer<byte[], byte[]> unused = KAFKA_GCS_TRUST_STORE_CONSUMER_FACTORY_FN.apply(originalMap)) {
            // Just here for auto close
          }

          Map<String, Object> expectedMap = ImmutableMap.of("someValue", targetPath.toAbsolutePath().toString());
          consumerMock.verify(() -> new KafkaConsumer<byte[], byte[]>(expectedMap));
        }
      }
    }
  }
}
