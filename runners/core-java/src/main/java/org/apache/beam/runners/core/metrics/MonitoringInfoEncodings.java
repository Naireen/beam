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
package org.apache.beam.runners.core.metrics;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Set;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.DoubleCoder;
import org.apache.beam.sdk.coders.IterableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VarLongCoder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.util.ByteStringOutputStream;
import org.apache.beam.sdk.util.HistogramData;
import org.apache.beam.vendor.grpc.v1p60p1.com.google.protobuf.ByteString;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Sets;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.api.services.dataflow.model.DataflowHistogramValue;
import com.google.api.services.dataflow.model.MetricValue;
import com.google.api.services.dataflow.model.OutlierStats;
import com.google.api.services.dataflow.model.Base2Exponent;
import com.google.api.services.dataflow.model.BucketOptions;
import com.google.api.services.dataflow.model.DataflowHistogramValue;
import com.google.api.services.dataflow.model.Linear;
import com.google.api.services.dataflow.model.MetricValue;
import com.google.api.services.dataflow.model.OutlierStats;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.io.ByteArrayOutputStream;
// import com.google.protobuf.contrib.ByteStrings;
import com.google.common.io.BaseEncoding;
import java.lang.reflect.Method;
import java.io.ObjectInputStream;


// TODO Refactor out DataflowHistogramValue to be runner agnostic.

/** A set of functions used to encode and decode common monitoring info types. */
public class MonitoringInfoEncodings {
  private static final Logger LOG = LoggerFactory.getLogger(MonitoringInfoEncodings.class);

  private static final Coder<Long> VARINT_CODER = VarLongCoder.of();
  private static final Coder<Double> DOUBLE_CODER = DoubleCoder.of();
  private static final Coder<String> STRING_CODER = StringUtf8Coder.of();
  private static final IterableCoder<String> STRING_SET_CODER =
      IterableCoder.of(STRING_CODER);
  // private static final Coder<Serializable> SERIALIZABLE_CODER =
  //     SerializableCoder.of();
  // private static final Coder<HistogramData> HISTOGRAM_CODER = SerializableCoder.of(DataflowHistogramValue.class);

  /** Encodes to {@link MonitoringInfoConstants.TypeUrns#DISTRIBUTION_INT64_TYPE}. */
  public static ByteString encodeInt64Distribution(DistributionData data) {
    ByteStringOutputStream output = new ByteStringOutputStream();
    try {
      VARINT_CODER.encode(data.count(), output);
      VARINT_CODER.encode(data.sum(), output);
      VARINT_CODER.encode(data.min(), output);
      VARINT_CODER.encode(data.max(), output);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return output.toByteString();
  }

  /** Encodes to {@link MonitoringInfoConstants.TypeUrns#PER_WORKER_HISTOGRAM}. */
  // encode specific fields from histogramData in DataflowHistogramValue
  public static ByteString encodeInt64Histogram(HistogramData inputHistogram) {
    LOG.info("Xxx: data {}", inputHistogram.getPercentileString("poll latency", "seconds"));
    ByteStringOutputStream output = new ByteStringOutputStream();
    // ByteArrayOutputStream output = new ByteArrayOutputStream();
    try {
      DataflowHistogramValue outputHistogram = new DataflowHistogramValue();
      int numberOfBuckets = inputHistogram.getBucketType().getNumBuckets();
      
      // refactor out different bucket types?
      if (inputHistogram.getBucketType() instanceof HistogramData.LinearBuckets) {
        HistogramData.LinearBuckets buckets = (HistogramData.LinearBuckets) inputHistogram.getBucketType();
        Linear linearOptions =
          new Linear()
              .setNumberOfBuckets(numberOfBuckets)
              .setWidth(buckets.getWidth())
              .setStart(buckets.getStart());
        outputHistogram.setBucketOptions(new BucketOptions().setLinear(linearOptions));
      } else if (inputHistogram.getBucketType() instanceof HistogramData.ExponentialBuckets) {
        HistogramData.ExponentialBuckets buckets = (HistogramData.ExponentialBuckets) inputHistogram.getBucketType();
        Base2Exponent expoenntialOptions = new Base2Exponent().setNumberOfBuckets(numberOfBuckets).setScale(buckets.getScale());
        outputHistogram.setBucketOptions(new BucketOptions().setExponential(expoenntialOptions));
      } else { // unsupported type
        // should an error be thrown here?
      }

      outputHistogram.setCount(inputHistogram.getTotalCount());
      List<Long> bucketCounts = new ArrayList<>(inputHistogram.getBucketType().getNumBuckets());
      for (int i = 0; i < inputHistogram.getBucketType().getNumBuckets(); i++) {
        bucketCounts.add(inputHistogram.getCount(i));
      }
      outputHistogram.setBucketCounts(bucketCounts);

      // set overflow stats
      OutlierStats outlierStats = new OutlierStats();
      long overflowCount = inputHistogram.getTopBucketCount();
      long underflowCount = inputHistogram.getBottomBucketCount();
      if (underflowCount > 0) {
        outlierStats
            .setUnderflowCount(underflowCount)
            .setUnderflowMean(inputHistogram.getBottomBucketMean());
      }
      if (overflowCount > 0) {
        outlierStats
            .setOverflowCount(overflowCount)
            .setOverflowMean(inputHistogram.getTopBucketMean());
      }

      LOG.info("xxx output {}",  outputHistogram.getClass()); 

      Method [] methods = outputHistogram.getClass().getMethods();
      for (Method method : methods) {
          System.out.println(method.toString()); 
      }
      // SerializableCoder<DataflowHistogramValue> coder = SerializableCoder.of(DataflowHistogramValue);
      // ByteString(), BaseEncoding.base64());
      // ByteString encoded_hist = outputHistogram.toByteString();
      // coder.encode(outputHistogram, output);
      // return encoded_hist;

      // convert it to string, then byte string
      STRING_CODER.encode(outputHistogram.toString(), output);
      return output.toByteString();

      // VARINT_CODER.encode(inputHistogram.getTotalCount(), output);
      // DOUBLE_CODER.encode(inputHistogram.p99(), output);
      // DOUBLE_CODER.encode(inputHistogram.p90(), output);
      // DOUBLE_CODER.encode(inputHistogram.p50(), output);

      // how to encode it? pass only percentiles for now?
      // encode snapshots to match v1 implementation?
      // encode snapshot equivalent?
      // Lower bound of a starting bucket.
      // for the number of buckets, get the size of the bucket, 
      // return output.toByteString();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Decodes to {@link MonitoringInfoConstants.TypeUrns#PER_WORKER_HISTOGRAM}. */
  public static HistogramData decodeInt64Histogram(ByteString payload) {
    // decode to DataflowHistogramValue, then create Histogram Data from it, and pass that along.
    InputStream input = payload.newInput();
    try {
      // DataflowHistogramValue.parseFrom(BaseEncoding.base64().decode(payload.toStringUtf8()));
      ObjectInputStream ois = new ObjectInputStream(input);
      // DataflowHistogramValue outputHistogram = (DataflowHistogramValue)input.readObject();
      // LOG.info("Xxx: parsed data {}", histogramData.getPercentileString("poll latency", "seconds"));
      DataflowHistogramValue outputHistogram = (YourCDataflowHistogramValuelass) deserializedObject;
      
      long count = VARINT_CODER.decode(input);
      double p99 = DOUBLE_CODER.decode(input);
      double p90 = DOUBLE_CODER.decode(input);
      double p59 = DOUBLE_CODER.decode(input);
      LOG.info("Xxx: data {} {} {} {}",count, p99, p90, p59);
      // return new HistogramData(HistogramData.LinearBuckets.of(0,5,5));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Decodes from {@link MonitoringInfoConstants.TypeUrns#DISTRIBUTION_INT64_TYPE}. */
  public static DistributionData decodeInt64Distribution(ByteString payload) {
    InputStream input = payload.newInput();
    try {
      long count = VARINT_CODER.decode(input);
      long sum = VARINT_CODER.decode(input);
      long min = VARINT_CODER.decode(input);
      long max = VARINT_CODER.decode(input);
      return DistributionData.create(sum, count, min, max);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Encodes to {@link MonitoringInfoConstants.TypeUrns#DISTRIBUTION_DOUBLE_TYPE}. */
  // TODO(BEAM-4374): Implement decodeDoubleDistribution(...)
  public static ByteString encodeDoubleDistribution(
      long count, double sum, double min, double max) {
    ByteStringOutputStream output = new ByteStringOutputStream();
    try {
      VARINT_CODER.encode(count, output);
      DOUBLE_CODER.encode(sum, output);
      DOUBLE_CODER.encode(min, output);
      DOUBLE_CODER.encode(max, output);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return output.toByteString();
  }

  /** Encodes to {@link MonitoringInfoConstants.TypeUrns#LATEST_INT64_TYPE}. */
  public static ByteString encodeInt64Gauge(GaugeData data) {
    ByteStringOutputStream output = new ByteStringOutputStream();
    try {
      VARINT_CODER.encode(data.timestamp().getMillis(), output);
      VARINT_CODER.encode(data.value(), output);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return output.toByteString();
  }

  /** Decodes from {@link MonitoringInfoConstants.TypeUrns#LATEST_INT64_TYPE}. */
  public static GaugeData decodeInt64Gauge(ByteString payload) {
    InputStream input = payload.newInput();
    try {
      Instant timestamp = new Instant(VARINT_CODER.decode(input));
      return GaugeData.create(VARINT_CODER.decode(input), timestamp);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Encodes to {@link MonitoringInfoConstants.TypeUrns#SET_STRING_TYPE}. */
  public static ByteString encodeStringSet(StringSetData data) {
    try (ByteStringOutputStream output = new ByteStringOutputStream()) {
      STRING_SET_CODER.encode(data.stringSet(), output);
      return output.toByteString();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Decodes from {@link MonitoringInfoConstants.TypeUrns#SET_STRING_TYPE}. */
  public static StringSetData decodeStringSet(ByteString payload) {
    try (InputStream input = payload.newInput()) {
      Set<String> elements = Sets.newHashSet(STRING_SET_CODER.decode(input));
      return StringSetData.create(elements);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Encodes to {@link MonitoringInfoConstants.TypeUrns#SUM_INT64_TYPE}. */
  public static ByteString encodeInt64Counter(long value) {
    ByteStringOutputStream output = new ByteStringOutputStream();
    try {
      VARINT_CODER.encode(value, output);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return output.toByteString();
  }

  /** Decodes from {@link MonitoringInfoConstants.TypeUrns#SUM_INT64_TYPE}. */
  public static long decodeInt64Counter(ByteString payload) {
    try {
      return VarLongCoder.of().decode(payload.newInput());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Encodes to {@link MonitoringInfoConstants.TypeUrns#SUM_DOUBLE_TYPE}. */
  public static ByteString encodeDoubleCounter(double value) {
    ByteStringOutputStream output = new ByteStringOutputStream();
    try {
      DOUBLE_CODER.encode(value, output);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return output.toByteString();
  }

  /** Decodes from {@link MonitoringInfoConstants.TypeUrns#SUM_DOUBLE_TYPE}. */
  public static double decodeDoubleCounter(ByteString payload) {
    try {
      return DOUBLE_CODER.decode(payload.newInput());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
