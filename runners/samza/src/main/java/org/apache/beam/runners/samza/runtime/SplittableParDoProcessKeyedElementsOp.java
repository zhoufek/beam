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
package org.apache.beam.runners.samza.runtime;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.beam.runners.core.DoFnRunner;
import org.apache.beam.runners.core.DoFnRunners;
import org.apache.beam.runners.core.KeyedWorkItem;
import org.apache.beam.runners.core.KeyedWorkItems;
import org.apache.beam.runners.core.NullSideInputReader;
import org.apache.beam.runners.core.OutputAndTimeBoundedSplittableProcessElementInvoker;
import org.apache.beam.runners.core.SplittableParDoViaKeyedWorkItems;
import org.apache.beam.runners.core.SplittableParDoViaKeyedWorkItems.ProcessElements;
import org.apache.beam.runners.core.StateInternals;
import org.apache.beam.runners.core.StateInternalsFactory;
import org.apache.beam.runners.core.StepContext;
import org.apache.beam.runners.core.TimerInternals;
import org.apache.beam.runners.core.TimerInternals.TimerData;
import org.apache.beam.runners.core.construction.SerializablePipelineOptions;
import org.apache.beam.runners.core.serialization.Base64Serializer;
import org.apache.beam.runners.samza.SamzaPipelineOptions;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.transforms.DoFnSchemaInformation;
import org.apache.beam.sdk.transforms.join.RawUnionValue;
import org.apache.beam.sdk.transforms.reflect.DoFnInvokers;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.WindowedValueMultiReceiver;
import org.apache.beam.sdk.util.construction.SplittableParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection.IsBounded;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.WindowedValue;
import org.apache.beam.sdk.values.WindowedValues;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.samza.config.Config;
import org.apache.samza.context.Context;
import org.apache.samza.operators.Scheduler;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Samza operator for {@link org.apache.beam.sdk.transforms.GroupByKey}. */
@SuppressWarnings({
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
public class SplittableParDoProcessKeyedElementsOp<
        InputT, OutputT, RestrictionT, PositionT, WatermarkEstimatorStateT>
    implements Op<KeyedWorkItem<byte[], KV<InputT, RestrictionT>>, RawUnionValue, byte[]> {
  private static final Logger LOG =
      LoggerFactory.getLogger(SplittableParDoProcessKeyedElementsOp.class);
  private static final String TIMER_STATE_ID = "timer";

  private final TupleTag<OutputT> mainOutputTag;
  private final WindowingStrategy<?, BoundedWindow> windowingStrategy;
  private final OutputManagerFactory<RawUnionValue> outputManagerFactory;
  private final SplittableParDoViaKeyedWorkItems.ProcessElements<
          InputT, OutputT, RestrictionT, PositionT, WatermarkEstimatorStateT>
      processElements;
  private final String transformId;
  private final IsBounded isBounded;

  private transient StateInternalsFactory<byte[]> stateInternalsFactory;
  private transient SamzaTimerInternalsFactory<byte[]> timerInternalsFactory;
  private transient DoFnRunner<KeyedWorkItem<byte[], KV<InputT, RestrictionT>>, OutputT> fnRunner;
  private transient SamzaPipelineOptions pipelineOptions;
  private transient @MonotonicNonNull ScheduledExecutorService ses = null;

  public SplittableParDoProcessKeyedElementsOp(
      TupleTag<OutputT> mainOutputTag,
      SplittableParDo.ProcessKeyedElements<InputT, OutputT, RestrictionT, WatermarkEstimatorStateT>
          processKeyedElements,
      WindowingStrategy<?, BoundedWindow> windowingStrategy,
      OutputManagerFactory<RawUnionValue> outputManagerFactory,
      String transformFullName,
      String transformId,
      IsBounded isBounded) {
    this.mainOutputTag = mainOutputTag;
    this.windowingStrategy = windowingStrategy;
    this.outputManagerFactory = outputManagerFactory;
    this.transformId = transformId;
    this.isBounded = isBounded;

    this.processElements = new ProcessElements<>(processKeyedElements);
  }

  @Override
  public void open(
      Config config,
      Context context,
      Scheduler<KeyedTimerData<byte[]>> timerRegistry,
      OpEmitter<RawUnionValue> emitter) {
    this.pipelineOptions =
        Base64Serializer.deserializeUnchecked(
                config.get("beamPipelineOptions"), SerializablePipelineOptions.class)
            .get()
            .as(SamzaPipelineOptions.class);

    final SamzaStoreStateInternals.Factory<?> nonKeyedStateInternalsFactory =
        SamzaStoreStateInternals.createNonKeyedStateInternalsFactory(
            transformId, context.getTaskContext(), pipelineOptions);

    final WindowedValueMultiReceiver outputManager = outputManagerFactory.create(emitter);

    this.stateInternalsFactory =
        new SamzaStoreStateInternals.Factory<>(
            transformId,
            Collections.singletonMap(
                SamzaStoreStateInternals.BEAM_STORE,
                SamzaStoreStateInternals.getBeamStore(context.getTaskContext())),
            ByteArrayCoder.of(),
            pipelineOptions.getStoreBatchGetSize());

    this.timerInternalsFactory =
        SamzaTimerInternalsFactory.createTimerInternalFactory(
            ByteArrayCoder.of(),
            timerRegistry,
            TIMER_STATE_ID,
            nonKeyedStateInternalsFactory,
            windowingStrategy,
            isBounded,
            pipelineOptions);

    if (this.ses == null) {
      this.ses =
          Executors.newSingleThreadScheduledExecutor(
              new ThreadFactoryBuilder().setNameFormat("samza-sdf-executor-%d").build());
    }

    final KeyedInternals<byte[]> keyedInternals =
        new KeyedInternals<>(stateInternalsFactory, timerInternalsFactory);

    SplittableParDoViaKeyedWorkItems.ProcessFn<
            InputT, OutputT, RestrictionT, PositionT, WatermarkEstimatorStateT>
        processFn = processElements.newProcessFn(processElements.getFn());
    DoFnInvokers.tryInvokeSetupFor(processFn, pipelineOptions);
    processFn.setStateInternalsFactory(stateInternalsFactory);
    processFn.setTimerInternalsFactory(timerInternalsFactory);
    processFn.setSideInputReader(NullSideInputReader.empty());
    processFn.setProcessElementInvoker(
        new OutputAndTimeBoundedSplittableProcessElementInvoker<>(
            processElements.getFn(),
            pipelineOptions,
            outputManager,
            mainOutputTag,
            NullSideInputReader.empty(),
            ses,
            10000,
            Duration.standardSeconds(10),
            () -> {
              throw new UnsupportedOperationException("BundleFinalizer unsupported in Samza");
            }));

    final StepContext stepContext =
        new StepContext() {
          @Override
          public StateInternals stateInternals() {
            return keyedInternals.stateInternals();
          }

          @Override
          public TimerInternals timerInternals() {
            return keyedInternals.timerInternals();
          }
        };

    this.fnRunner =
        DoFnRunners.simpleRunner(
            pipelineOptions,
            processFn,
            NullSideInputReader.of(Collections.emptyList()),
            outputManager,
            mainOutputTag,
            Collections.emptyList(),
            stepContext,
            null,
            Collections.emptyMap(),
            windowingStrategy,
            DoFnSchemaInformation.create(),
            Collections.emptyMap());
  }

  @Override
  public void processElement(
      WindowedValue<KeyedWorkItem<byte[], KV<InputT, RestrictionT>>> inputElement,
      OpEmitter<RawUnionValue> emitter) {
    fnRunner.startBundle();
    fnRunner.processElement(inputElement);
    fnRunner.finishBundle();
  }

  @Override
  public void processWatermark(Instant watermark, OpEmitter<RawUnionValue> emitter) {
    timerInternalsFactory.setInputWatermark(watermark);

    Collection<KeyedTimerData<byte[]>> readyTimers = timerInternalsFactory.removeReadyTimers();
    if (!readyTimers.isEmpty()) {
      fnRunner.startBundle();
      for (KeyedTimerData<byte[]> keyedTimerData : readyTimers) {
        fireTimer(keyedTimerData.getKey(), keyedTimerData.getTimerData());
      }
      fnRunner.finishBundle();
    }

    if (timerInternalsFactory.getOutputWatermark() == null
        || timerInternalsFactory.getOutputWatermark().isBefore(watermark)) {
      timerInternalsFactory.setOutputWatermark(watermark);
      emitter.emitWatermark(timerInternalsFactory.getOutputWatermark());
    }
  }

  @Override
  public void processTimer(
      KeyedTimerData<byte[]> keyedTimerData, OpEmitter<RawUnionValue> emitter) {
    fnRunner.startBundle();
    fireTimer(keyedTimerData.getKey(), keyedTimerData.getTimerData());
    fnRunner.finishBundle();

    timerInternalsFactory.removeProcessingTimer(keyedTimerData);
  }

  private void fireTimer(byte[] key, TimerData timer) {
    LOG.debug("Firing timer {} for key {}", timer, key);
    fnRunner.processElement(
        WindowedValues.valueInGlobalWindow(
            KeyedWorkItems.timersWorkItem(key, Collections.singletonList(timer))));
  }
}
