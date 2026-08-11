package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Bounded encoder for the inactive V1 connection-lifecycle slice. */
public final class V1JsonLifecycleCodec {
    public static final int MAX_LIFECYCLE_WIRE_BYTES = 4 * 1024;
    private static final String REPLACED_REASON =
            "您的账号在其他地方登录，当前连接已被断开";

    private final JsonFactory json;
    private final Clock clock;

    public enum MessageKind {
        HEARTBEAT,
        HEARTBEAT_ACK,
        OTHER
    }

    public V1JsonLifecycleCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(8)
                        .maxStringLength(1024)
                        .maxNumberLength(32)
                        .build())
                .streamWriteConstraints(StreamWriteConstraints.builder()
                        .maxNestingDepth(4)
                        .build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
                .build();
    }

    /** Classifies only small, valid lifecycle envelopes; all other input stays downstream-owned. */
    public MessageKind classify(byte[] wire) {
        Objects.requireNonNull(wire, "wire");
        if (wire.length == 0 || wire.length > MAX_LIFECYCLE_WIRE_BYTES) {
            return MessageKind.OTHER;
        }
        String type = null;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return MessageKind.OTHER;
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    return MessageKind.OTHER;
                }
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) {
                        return MessageKind.OTHER;
                    }
                    type = parser.getText();
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                return MessageKind.OTHER;
            }
        } catch (IOException | RuntimeException exception) {
            return MessageKind.OTHER;
        }
        if ("HEARTBEAT".equals(type)) {
            return MessageKind.HEARTBEAT;
        }
        if ("HEARTBEAT_ACK".equals(type)) {
            return MessageKind.HEARTBEAT_ACK;
        }
        return MessageKind.OTHER;
    }

    public byte[] encodeHeartbeatAck() {
        return encode("HEARTBEAT_ACK", null);
    }

    public byte[] encodeForceOffline() {
        return encode("FORCE_OFFLINE", REPLACED_REASON);
    }

    private byte[] encode(String type, String reason) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(256);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeStringField("type", type);
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            if (reason != null) {
                generator.writeStringField("reason", reason);
            }
            generator.writeEndObject();
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 lifecycle response encoding failed", exception);
        }
        byte[] encoded = output.toByteArray();
        if (encoded.length > MAX_LIFECYCLE_WIRE_BYTES) {
            throw new IllegalStateException("V1 lifecycle response exceeded its fixed bound");
        }
        return encoded;
    }
}
