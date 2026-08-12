package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1PendingFriendRequest;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1PendingFriendRequestService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded codec for V1 FRIEND_PENDING_REQ/RSP. */
public final class V1JsonPendingFriendRequestCodec {
    public enum RequestKind { PENDING, MALFORMED_PENDING, OTHER }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private final Clock clock;

    public V1JsonPendingFriendRequestCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RequestKind classify(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > 4096) return RequestKind.OTHER;
        String type = null;
        boolean dataObject = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return RequestKind.OTHER;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    return "FRIEND_PENDING_REQ".equals(type)
                            ? RequestKind.MALFORMED_PENDING : RequestKind.OTHER;
                }
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return RequestKind.OTHER;
                    type = parser.getText();
                } else if ("data".equals(field)) {
                    dataObject = value == JsonToken.START_OBJECT;
                    parser.skipChildren();
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return "FRIEND_PENDING_REQ".equals(type)
                    ? RequestKind.MALFORMED_PENDING : RequestKind.OTHER;
        } catch (IOException | RuntimeException exception) {
            return "FRIEND_PENDING_REQ".equals(type)
                    ? RequestKind.MALFORMED_PENDING : RequestKind.OTHER;
        }
        if (!"FRIEND_PENDING_REQ".equals(type)) return RequestKind.OTHER;
        return dataObject ? RequestKind.PENDING : RequestKind.MALFORMED_PENDING;
    }

    public byte[] encode(List<LegacyV1PendingFriendRequest> requests) {
        Objects.requireNonNull(requests, "requests");
        if (requests.size() > LegacyV1PendingFriendRequestService.MAX_PENDING_REQUESTS) {
            throw new IllegalArgumentException("pending response exceeds row bound");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeStringField("type", "FRIEND_PENDING_RSP");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            generator.writeArrayFieldStart("requests");
            for (LegacyV1PendingFriendRequest request : requests) {
                generator.writeStartObject();
                generator.writeNumberField("requestId", request.requestId());
                generator.writeNumberField("fromUserId", request.fromUserId());
                generator.writeStringField("fromUsername", request.fromUsername());
                generator.writeStringField("fromDisplayName", request.fromDisplayName());
                generator.writeNumberField("timestamp", request.createdAt().toEpochMilli());
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeEndObject();
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 pending request encoding failed", exception);
        }
        byte[] encoded = output.toByteArray();
        if (encoded.length > 1024 * 1024) {
            throw new IllegalStateException("V1 pending response exceeded wire bound");
        }
        return encoded;
    }
}
