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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.beam.sdk.io.kafka.KafkaIOUtils.FactoryWithGcsTrustStore;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.util.FileDownloader;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

/** Tests of {@link KafkaIOUtils}. */
@RunWith(PowerMockRunner.class)
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
  @PrepareForTest({
    FileDownloader.class,
    FactoryWithGcsTrustStore.class,
  })
  public void testGcsFactoryOverridesGcsFilePaths() throws Exception {
    // Arrange
    String gcsPath = "gs://path/to/gcs/file";
    Map<String, Object> originalMap =
        new HashMap<>(ImmutableMap.of("someConfig", gcsPath, "someOtherConfig", "foo"));
    Path targetPath = Paths.get("");

    mockStatic(Files.class);
    when(Files.createTempFile(any(), any())).thenAnswer(invocation -> targetPath);

    mockStatic(FileDownloader.class);
    when(FileDownloader.class, "download", any(), any()).thenAnswer(invocation -> null);

    FakeKafkaConsumerFactory baseFactory = new FakeKafkaConsumerFactory();

    // Act
    new FactoryWithGcsTrustStore<>(baseFactory).apply(originalMap);

    // Assert
    Map<String, Object> expectedMap =
        new HashMap<>(
            ImmutableMap.of(
                "someConfig", targetPath.toAbsolutePath().toString(), "someOtherConfig", "foo"));
    assertEquals(baseFactory.callArgsSoFar(), ImmutableList.of(expectedMap));
  }

  private abstract static class FakeKafkaFactory<T>
      implements SerializableFunction<Map<String, Object>, T> {
    private final List<ImmutableMap<String, Object>> callArgs;

    FakeKafkaFactory() {
      this.callArgs = new ArrayList<>();
    }

    ImmutableList<ImmutableMap<String, Object>> callArgsSoFar() {
      return ImmutableList.copyOf(callArgs);
    }

    protected void recordCallArg(Map<String, Object> arg) {
      callArgs.add(ImmutableMap.copyOf(arg));
    }
  }

  private static final class FakeKafkaConsumerFactory
      extends FakeKafkaFactory<Consumer<byte[], byte[]>> {

    FakeKafkaConsumerFactory() {
      super();
    }

    @Override
    public Consumer<byte[], byte[]> apply(Map<String, Object> input) {
      recordCallArg(input);
      return new MockConsumer<>(OffsetResetStrategy.NONE);
    }
  }
}
