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
package org.apache.beam.sdk.io.gcp.pubsub;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Map;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;
// import com.google.pubsub.v1.PubsubMessage;

/** Common util functions for converting between PubsubMessage proto and {@link PubsubMessage}. */
public final class PubsubMessages {
  private PubsubMessages() {}

  public static com.google.pubsub.v1.PubsubMessage toProto(PubsubMessage input) {
    Map<String, String> attributes = input.getAttributeMap();
    com.google.pubsub.v1.PubsubMessage.Builder message =
        com.google.pubsub.v1.PubsubMessage.newBuilder()
            .setData(ByteString.copyFrom(input.getPayload()));
    // TODO(https://github.com/apache/beam/issues/19787) this should not be null
    if (attributes != null) {
      message.putAllAttributes(attributes);
    }
    String messageId = input.getMessageId();
    if (messageId != null) {
      message.setMessageId(messageId);
    }

    String orderingKey = input.getOrderingKey();
    if (orderingKey != null) {
      message.setOrderingKey(orderingKey);
    }
    return message.build();
  }

  public static PubsubMessage fromProto(com.google.pubsub.v1.PubsubMessage input) {
    return new PubsubMessage(
        input.getData().toByteArray(),
        input.getAttributesMap(),
        input.getMessageId(),
        input.getOrderingKey());
  }

  // Convert the PubsubMessage to a PubsubMessage proto, then return its serialized representation.
  public static class ParsePayloadAsPubsubMessageProto
      implements SerializableFunction<PubsubMessage, byte[]> {
    @Override
    public byte[] apply(PubsubMessage input) {
      return toProto(input).toByteArray();
    }
  }

  // Convert the serialized PubsubMessage proto to PubsubMessage.
  public static class ParsePubsubMessageProtoAsPayload
      implements SerializableFunction<byte[], PubsubMessage> {
    @Override
    public PubsubMessage apply(byte[] input) {
      try {
        com.google.pubsub.v1.PubsubMessage message =
            com.google.pubsub.v1.PubsubMessage.parseFrom(input);
        return fromProto(message);
      } catch (InvalidProtocolBufferException e) {
        throw new RuntimeException("Could not decode Pubsub message", e);
      }
    }
  }

  // Convert the PubsubMessage to a PubsubMessage proto, then return its serialized representation.
  public static class ParsePayloadAsPubsubMessageProtoWithTopicFn
      implements SerializableFunction<PubsubMessage, KV<String, byte[]>> {

    private final SerializableFunction<PubsubMessage, String> topicFn;

    public ParsePayloadAsPubsubMessageProtoWithTopicFn(
        SerializableFunction<PubsubMessage, String> topicFn) {
      this.topicFn = topicFn;
    }

    @Override
    public KV<String, byte[]> apply(PubsubMessage input) {
      return KV.of(this.topicFn.apply(input), toProto(input).toByteArray());
    }
  }

  // Convert the PubsubMessage to a PubsubMessage proto, then return its serialized representation.
  public static class ParsePayloadAsPubsubMessageProtoWithTopic
      implements SerializableFunction<PubsubMessage, KV<String, byte[]>> {

    private final String topic;

    public ParsePayloadAsPubsubMessageProtoWithTopic(String topic) {
      this.topic = topic;
    }

    @Override
    public KV<String, byte[]> apply(PubsubMessage input) {
      return KV.of(this.topic, toProto(input).toByteArray());
    }
  }
  public static class DeserializeBytesIntoPubsubMessagePayloadOnly
      implements SerializableFunction<byte[], PubsubMessage> {

    @Override
    public PubsubMessage apply(byte[] value) {
      return new PubsubMessage(value, ImmutableMap.of());
    }
  }
}
