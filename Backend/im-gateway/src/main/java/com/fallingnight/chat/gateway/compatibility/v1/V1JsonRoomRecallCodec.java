package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomRecallResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict V1 RECALL_REQ/RSP/NOTIFY codec. */
public final class V1JsonRoomRecallCodec {
    public enum RequestKind { RECALL, MALFORMED_RECALL, OTHER }
    public record DecodedRequest(RequestKind kind, long legacyRoomId,
            long legacyMessageId) { }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;
    public V1JsonRoomRecallCodec(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > 4096) return other();
        String type = null; Data data = null;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfRecall(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other(); type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    data = readData(parser);
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return malformedIfRecall(type);
        } catch (IOException | RuntimeException exception) { return malformedIfRecall(type); }
        if (!"RECALL_REQ".equals(type)) return other();
        return data == null ? malformed()
                : new DecodedRequest(RequestKind.RECALL, data.roomId(), data.messageId());
    }

    private static Data readData(JsonParser parser) throws IOException {
        Long roomId = null, messageId = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            try {
                if ("roomId".equals(field)) {
                    if (value != JsonToken.VALUE_NUMBER_INT) invalid = true;
                    else roomId = parser.getLongValue();
                } else if ("messageId".equals(field)) {
                    if (value != JsonToken.VALUE_NUMBER_INT) invalid = true;
                    else messageId = parser.getLongValue();
                } else invalid = true;
            } catch (RuntimeException exception) { invalid = true; }
            if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) parser.skipChildren();
        }
        return invalid || roomId == null || messageId == null ? null : new Data(roomId, messageId);
    }

    public byte[] encodeResponse(LegacyV1RoomRecallResult result,
            long requestRoomId, long requestMessageId) {
        return encode("RECALL_RSP", generator -> {
            generator.writeNumberField("roomId", requestRoomId);
            generator.writeNumberField("messageId", requestMessageId);
            generator.writeBooleanField("success", result instanceof LegacyV1RoomRecallResult.Recalled);
            if (result instanceof LegacyV1RoomRecallResult.Recalled recalled) {
                generator.writeBooleanField("duplicate", recalled.duplicate());
                generator.writeNumberField("mutationSequence", recalled.mutationSequence());
            } else {
                var rejected = (LegacyV1RoomRecallResult.Rejected) result;
                generator.writeStringField("errorCode", switch (rejected) {
                    case ROOM_ACCESS_DENIED -> "RECALL_ACCESS_DENIED";
                    case RECALL_REJECTED -> "RECALL_REJECTED";
                    case INVALID_REQUEST -> "INVALID_REQUEST";
                });
                generator.writeStringField("error", rejected ==
                        LegacyV1RoomRecallResult.Rejected.ROOM_ACCESS_DENIED
                        ? "无权撤回该消息" : "无法撤回（超时或非本人消息）");
            }
        });
    }

    public byte[] encodeNotification(LegacyV1RoomRecallResult.Recalled recalled,
            String actorUsername) {
        return encode("RECALL_NOTIFY", generator -> {
            generator.writeNumberField("messageId", recalled.legacyMessageId());
            generator.writeNumberField("roomId", recalled.legacyRoomId());
            generator.writeStringField("username", actorUsername);
            generator.writeNumberField("mutationSequence", recalled.mutationSequence());
        });
    }

    private byte[] encode(String type, Fields fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", type);
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data"); fields.write(generator);
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
        return output.toByteArray();
    }
    private static DecodedRequest malformedIfRecall(String type) {
        return "RECALL_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_RECALL, 0, 0);
    }
    private static DecodedRequest other() { return new DecodedRequest(RequestKind.OTHER, 0, 0); }
    private record Data(long roomId, long messageId) { }
    @FunctionalInterface private interface Fields { void write(JsonGenerator generator) throws IOException; }
}
