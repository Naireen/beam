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
package org.apache.beam.sdk.testing;

import static org.apache.beam.sdk.transforms.windowing.Window.into;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

import java.io.Serializable;
import java.util.stream.StreamSupport;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.coders.VarLongCoder;
import org.apache.beam.sdk.state.StateSpec;
import org.apache.beam.sdk.state.StateSpecs;
import org.apache.beam.sdk.state.TimeDomain;
import org.apache.beam.sdk.state.Timer;
import org.apache.beam.sdk.state.TimerSpec;
import org.apache.beam.sdk.state.TimerSpecs;
import org.apache.beam.sdk.state.ValueState;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.Keys;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Sum;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.transforms.windowing.AfterPane;
import org.apache.beam.sdk.transforms.windowing.AfterProcessingTime;
import org.apache.beam.sdk.transforms.windowing.AfterWatermark;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.DefaultTrigger;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.Never;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.transforms.windowing.Window.ClosingBehavior;
import org.apache.beam.sdk.util.CoderUtils;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.TimestampedValue;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableList;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link TestStream}. */
@RunWith(JUnit4.class)
// TODO(https://github.com/apache/beam/issues/21230): Remove when new version of errorprone is
// released (2.11.0)
@SuppressWarnings("unused")
public class TestStreamTest implements Serializable {
  @Rule public transient TestPipeline p = TestPipeline.create();
  @Rule public transient ExpectedException thrown = ExpectedException.none();

  @Test
  @Category({ValidatesRunner.class, UsesTestStream.class})
  public void testLateDataAccumulating() {
    Instant instant = new Instant(0);
    TestStream<Long> source =
        TestStream.create(VarLongCoder.of())
            .addElements(
                TimestampedValue.of(1L, instant),
                TimestampedValue.of(2L, instant),
                TimestampedValue.of(3L, instant))
            .advanceWatermarkTo(instant.plus(Duration.standardMinutes(6)))
            // These elements are late but within the allowed lateness
            .addElements(TimestampedValue.of(4L, instant), TimestampedValue.of(5L, instant))
            .advanceWatermarkTo(instant.plus(Duration.standardMinutes(10)))
            // FIXME: Without advancing the watermark once more the (lower) input watermark remains
            // at 6 mins, but data in [0,5 min) won't be considered late until it passes 10 mins.
            .advanceWatermarkTo(instant.plus(Duration.standardMinutes(20)))
            // These elements are droppably late
            .addElements(
                TimestampedValue.of(-1L, instant),
                TimestampedValue.of(-2L, instant),
                TimestampedValue.of(-3L, instant))
            .advanceWatermarkToInfinity();

    PCollection<Long> windowed =
        p.apply(source)
            .apply(
                Window.<Long>into(FixedWindows.of(Duration.standardMinutes(5)))
                    .triggering(
                        AfterWatermark.pastEndOfWindow()
                            .withEarlyFirings(
                                AfterProcessingTime.pastFirstElementInPane()
                                    .plusDelayOf(Duration.standardMinutes(2)))
                            .withLateFirings(AfterPane.elementCountAtLeast(1)))
                    .accumulatingFiredPanes()
                    .withAllowedLateness(Duration.standardMinutes(5), ClosingBehavior.FIRE_ALWAYS));
    PCollection<Long> triggered =
        windowed
            .apply(WithKeys.of(1))
            .apply(GroupByKey.create())
            .apply(Values.create())
            .apply(Flatten.iterables());
    PCollection<Long> count =
        windowed.apply(Combine.globally(Count.<Long>combineFn()).withoutDefaults());
    PCollection<Long> sum = windowed.apply(Sum.longsGlobally().withoutDefaults());

    IntervalWindow window = new IntervalWindow(instant, instant.plus(Duration.standardMinutes(5L)));
    PAssert.that(triggered).inFinalPane(window).containsInAnyOrder(1L, 2L, 3L, 4L, 5L);
    PAssert.that(triggered).inOnTimePane(window).containsInAnyOrder(1L, 2L, 3L);
    PAssert.that(count)
        .inWindow(window)
        .satisfies(
            input -> {
              for (Long count1 : input) {
                assertThat(count1, allOf(greaterThanOrEqualTo(3L), lessThanOrEqualTo(5L)));
              }
              return null;
            });
    PAssert.that(sum)
        .inWindow(window)
        .satisfies(
            input -> {
              for (Long sum1 : input) {
                assertThat(sum1, allOf(greaterThanOrEqualTo(6L), lessThanOrEqualTo(15L)));
              }
              return null;
            });

    p.run();
  }

  @Test
  @Category({ValidatesRunner.class, UsesTestStreamWithProcessingTime.class})
  public void testProcessingTimeTrigger() {
    TestStream<Long> source =
        TestStream.create(VarLongCoder.of())
            .addElements(
                TimestampedValue.of(1L, new Instant(1000L)),
                TimestampedValue.of(2L, new Instant(2000L)))
            .advanceProcessingTime(Duration.standardMinutes(12))
            .addElements(TimestampedValue.of(3L, new Instant(3000L)))
            .advanceProcessingTime(Duration.standardMinutes(6))
            .advanceWatermarkToInfinity();

    PCollection<Long> sum =
        p.apply(source)
            .apply(
                Window.<Long>configure()
                    .triggering(
                        AfterWatermark.pastEndOfWindow()
                            .withEarlyFirings(
                                AfterProcessingTime.pastFirstElementInPane()
                                    .plusDelayOf(Duration.standardMinutes(5))))
                    .accumulatingFiredPanes()
                    .withAllowedLateness(Duration.ZERO))
            .apply(Sum.longsGlobally());

    PAssert.that(sum).inEarlyGlobalWindowPanes().containsInAnyOrder(3L, 6L);

    p.run();
  }

  @Test
  @Category({ValidatesRunner.class, UsesTestStream.class})
  public void testDiscardingMode() {
    TestStream<String> stream =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0))
            .addElements(
                TimestampedValue.of("firstPane", new Instant(100)),
                TimestampedValue.of("alsoFirstPane", new Instant(200)))
            .addElements(TimestampedValue.of("onTimePane", new Instant(500)))
            .advanceWatermarkTo(new Instant(1000L))
            .addElements(
                TimestampedValue.of("finalLatePane", new Instant(750)),
                TimestampedValue.of("alsoFinalLatePane", new Instant(250)))
            .advanceWatermarkToInfinity();

    FixedWindows windowFn = FixedWindows.of(Duration.millis(1000L));
    Duration allowedLateness = Duration.millis(5000L);
    PCollection<String> values =
        p.apply(stream)
            .apply(
                Window.<String>into(windowFn)
                    .triggering(
                        AfterWatermark.pastEndOfWindow()
                            .withEarlyFirings(AfterPane.elementCountAtLeast(2))
                            .withLateFirings(Never.ever()))
                    .discardingFiredPanes()
                    .withAllowedLateness(allowedLateness))
            .apply(WithKeys.of(1))
            .apply(GroupByKey.create())
            .apply(Values.create())
            .apply(Flatten.iterables());

    IntervalWindow window = windowFn.assignWindow(new Instant(100));
    PAssert.that(values)
        .inWindow(window)
        .containsInAnyOrder(
            "firstPane", "alsoFirstPane", "onTimePane", "finalLatePane", "alsoFinalLatePane");
    PAssert.that(values)
        .inCombinedNonLatePanes(window)
        .containsInAnyOrder("firstPane", "alsoFirstPane", "onTimePane");
    PAssert.that(values).inOnTimePane(window).containsInAnyOrder("onTimePane");
    PAssert.that(values)
        .inFinalPane(window)
        .containsInAnyOrder("finalLatePane", "alsoFinalLatePane");

    p.run();
  }

  @Test
  @Category({ValidatesRunner.class, UsesTestStream.class})
  public void testFirstElementLate() {
    Instant lateElementTimestamp = new Instant(-1_000_000);
    TestStream<String> stream =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0))
            .addElements(TimestampedValue.of("late", lateElementTimestamp))
            .addElements(TimestampedValue.of("onTime", new Instant(100)))
            .advanceWatermarkToInfinity();

    FixedWindows windowFn = FixedWindows.of(Duration.millis(1000L));
    Duration allowedLateness = Duration.millis(5000L);
    PCollection<String> values =
        p.apply(stream)
            .apply(
                Window.<String>into(windowFn)
                    .triggering(DefaultTrigger.of())
                    .discardingFiredPanes()
                    .withAllowedLateness(allowedLateness))
            .apply(WithKeys.of(1))
            .apply(GroupByKey.create())
            .apply(Values.create())
            .apply(Flatten.iterables());

    PAssert.that(values).inWindow(windowFn.assignWindow(lateElementTimestamp)).empty();
    PAssert.that(values)
        .inWindow(windowFn.assignWindow(new Instant(100)))
        .containsInAnyOrder("onTime");

    p.run();
  }

  @Test
  @Category({ValidatesRunner.class, UsesTestStream.class})
  public void testElementsAtAlmostPositiveInfinity() {
    Instant endOfGlobalWindow = GlobalWindow.INSTANCE.maxTimestamp();
    TestStream<String> stream =
        TestStream.create(StringUtf8Coder.of())
            .addElements(
                TimestampedValue.of("foo", endOfGlobalWindow),
                TimestampedValue.of("bar", endOfGlobalWindow))
            .advanceWatermarkToInfinity();

    FixedWindows windows = FixedWindows.of(Duration.standardHours(6));
    PCollection<String> windowedValues =
        p.apply(stream)
            .apply(into(windows))
            .apply(WithKeys.of(1))
            .apply(GroupByKey.create())
            .apply(Values.create())
            .apply(Flatten.iterables());

    PAssert.that(windowedValues)
        .inWindow(windows.assignWindow(endOfGlobalWindow))
        .containsInAnyOrder("foo", "bar");
    p.run();
  }

  @Test
  @Category({ValidatesRunner.class, UsesTestStream.class})
  public void testMultipleStreams() {
    TestStream<String> stream =
        TestStream.create(StringUtf8Coder.of())
            .addElements("foo", "bar")
            .advanceWatermarkToInfinity();

    TestStream<Long> other =
        TestStream.create(VarLongCoder.of())
            .addElements(1L, 2L, 3L, 4L)
            .advanceWatermarkToInfinity();

    PCollection<String> createStrings =
        p.apply("CreateStrings", stream)
            .apply(
                "WindowStrings",
                Window.<String>configure()
                    .triggering(AfterPane.elementCountAtLeast(2))
                    .withAllowedLateness(Duration.ZERO)
                    .accumulatingFiredPanes());
    PAssert.that(createStrings).containsInAnyOrder("foo", "bar");
    PCollection<Long> createInts =
        p.apply("CreateInts", other)
            .apply(
                "WindowInts",
                Window.<Long>configure()
                    .triggering(AfterPane.elementCountAtLeast(4))
                    .withAllowedLateness(Duration.ZERO)
                    .accumulatingFiredPanes());
    PAssert.that(createInts).containsInAnyOrder(1L, 2L, 3L, 4L);

    p.run();
  }

  @Test
  public void testElementAtPositiveInfinityThrows() {
    TestStream.Builder<Integer> stream =
        TestStream.create(VarIntCoder.of())
            .addElements(
                TimestampedValue.of(
                    -1, BoundedWindow.TIMESTAMP_MAX_VALUE.minus(Duration.millis(1L))));
    thrown.expect(IllegalArgumentException.class);
    stream.addElements(TimestampedValue.of(1, BoundedWindow.TIMESTAMP_MAX_VALUE));
  }

  @Test
  public void testAdvanceWatermarkNonMonotonicThrows() {
    TestStream.Builder<Integer> stream =
        TestStream.create(VarIntCoder.of()).advanceWatermarkTo(new Instant(0L));
    thrown.expect(IllegalArgumentException.class);
    stream.advanceWatermarkTo(new Instant(-1L));
  }

  @Test
  public void testAdvanceWatermarkEqualToPositiveInfinityThrows() {
    TestStream.Builder<Integer> stream =
        TestStream.create(VarIntCoder.of())
            .advanceWatermarkTo(BoundedWindow.TIMESTAMP_MAX_VALUE.minus(Duration.millis(1L)));
    thrown.expect(IllegalArgumentException.class);
    stream.advanceWatermarkTo(BoundedWindow.TIMESTAMP_MAX_VALUE);
  }

  @Test
  @Category({ValidatesRunner.class, UsesTestStreamWithProcessingTime.class})
  public void testEarlyPanesOfWindow() {
    TestStream<Long> source =
        TestStream.create(VarLongCoder.of())
            .addElements(TimestampedValue.of(1L, new Instant(1000L)))
            .advanceProcessingTime(Duration.standardMinutes(6)) // Fire early paneInfo
            .addElements(TimestampedValue.of(2L, new Instant(2000L)))
            .advanceProcessingTime(Duration.standardMinutes(6)) // Fire early paneInfo
            .addElements(TimestampedValue.of(3L, new Instant(3000L)))
            .advanceProcessingTime(Duration.standardMinutes(6)) // Fire early paneInfo
            .advanceWatermarkToInfinity(); // Fire on-time paneInfo

    PCollection<KV<String, Long>> sum =
        p.apply(source)
            .apply(
                Window.<Long>into(FixedWindows.of(Duration.standardMinutes(30)))
                    .triggering(
                        AfterWatermark.pastEndOfWindow()
                            .withEarlyFirings(
                                AfterProcessingTime.pastFirstElementInPane()
                                    .plusDelayOf(Duration.standardMinutes(5))))
                    .accumulatingFiredPanes()
                    .withAllowedLateness(Duration.ZERO))
            .apply(
                MapElements.into(
                        TypeDescriptors.kvs(TypeDescriptors.strings(), TypeDescriptors.longs()))
                    .via(v -> KV.of("key", v)))
            .apply(Sum.longsPerKey());

    IntervalWindow window =
        new IntervalWindow(new Instant(0L), new Instant(0L).plus(Duration.standardMinutes(30)));

    PAssert.that(sum)
        .inEarlyPane(window)
        .satisfies(
            input -> {
              assertThat(StreamSupport.stream(input.spliterator(), false).count(), is(3L));
              return null;
            })
        .containsInAnyOrder(KV.of("key", 1L), KV.of("key", 3L), KV.of("key", 6L))
        .inOnTimePane(window)
        .satisfies(
            input -> {
              assertThat(StreamSupport.stream(input.spliterator(), false).count(), is(1L));
              return null;
            })
        .containsInAnyOrder(KV.of("key", 6L));

    p.run().waitUntilFinish();
  }

  @Test
  @Category({
    ValidatesRunner.class,
    UsesTestStream.class,
    UsesTestStreamWithMultipleStages.class,
    UsesStatefulParDo.class
  })
  public void testMultiStage() throws Exception {
    TestStream<String> testStream =
        TestStream.create(StringUtf8Coder.of())
            .addElements("before") // before
            .advanceWatermarkTo(Instant.ofEpochSecond(0)) // BEFORE
            .addElements(TimestampedValue.of("after", Instant.ofEpochSecond(10))) // after
            .advanceWatermarkToInfinity(); // AFTER

    PCollection<String> input = p.apply(testStream);

    PCollection<String> grouped =
        input
            .apply(Window.into(FixedWindows.of(Duration.standardSeconds(1))))
            .apply(
                MapElements.into(
                        TypeDescriptors.kvs(TypeDescriptors.strings(), TypeDescriptors.strings()))
                    .via(e -> KV.of(e, e)))
            .apply(GroupByKey.create())
            .apply(Keys.create())
            .apply("Upper", MapElements.into(TypeDescriptors.strings()).via(String::toUpperCase))
            .apply("Rewindow", Window.into(new GlobalWindows()));

    PCollection<String> result =
        PCollectionList.of(ImmutableList.of(input, grouped))
            .apply(Flatten.pCollections())
            .apply(
                "Key",
                MapElements.into(
                        TypeDescriptors.kvs(TypeDescriptors.strings(), TypeDescriptors.strings()))
                    .via(e -> KV.of("key", e)))
            .apply(
                ParDo.of(
                    new DoFn<KV<String, String>, String>() {

                      @StateId("seen")
                      private final StateSpec<ValueState<String>> seenSpec =
                          StateSpecs.value(StringUtf8Coder.of());

                      @TimerId("emit")
                      private final TimerSpec emitSpec = TimerSpecs.timer(TimeDomain.EVENT_TIME);

                      @ProcessElement
                      public void process(
                          ProcessContext context,
                          @StateId("seen") ValueState<String> seenState,
                          @TimerId("emit") Timer emitTimer) {
                        String element = context.element().getValue();
                        if (seenState.read() == null) {
                          seenState.write(element);
                        } else {
                          seenState.write(seenState.read() + "," + element);
                        }
                        emitTimer.set(Instant.ofEpochSecond(100));
                      }

                      @OnTimer("emit")
                      public void onEmit(
                          OnTimerContext context, @StateId("seen") ValueState<String> seenState) {
                        context.output(seenState.read());
                      }
                    }));

    PAssert.that(result).containsInAnyOrder("before,BEFORE,after,AFTER");

    p.run().waitUntilFinish();
  }

  @Test
  public void testTestStreamCoder() throws Exception {
    TestStream<String> testStream =
        TestStream.create(StringUtf8Coder.of())
            .addElements("hey")
            .advanceWatermarkTo(Instant.ofEpochMilli(22521600))
            .advanceProcessingTime(Duration.millis(42))
            .addElements("hey", "joe")
            .advanceWatermarkToInfinity();

    TestStream.TestStreamCoder<String> coder = TestStream.TestStreamCoder.of(StringUtf8Coder.of());

    byte[] bytes = CoderUtils.encodeToByteArray(coder, testStream);
    TestStream<String> recoveredStream = CoderUtils.decodeFromByteArray(coder, bytes);

    assertThat(recoveredStream, is(testStream));
  }
}
