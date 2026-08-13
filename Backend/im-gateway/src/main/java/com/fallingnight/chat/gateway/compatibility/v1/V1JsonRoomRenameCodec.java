package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomRenameResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded V1 RENAME_ROOM request, response, and notification codec. */
public final class V1JsonRoomRenameCodec {
    public static final int MAX_REQUEST_BYTES = 4096;
    public enum RequestKind { RENAME, MALFORMED, OTHER }
    public record DecodedRequest(RequestKind kind, long roomId, String newName) { }
    public record Notifications(byte[] renamed, byte[] systemMessage) { }

    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(512).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;
    public V1JsonRoomRenameCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > MAX_REQUEST_BYTES) return other();
        String type = null; Data data = null;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return owned(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    data = readData(parser);
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return owned(type);
        } catch (IOException | RuntimeException exception) { return owned(type); }
        if (!"RENAME_ROOM_REQ".equals(type)) return other();
        return data == null ? malformed()
                : new DecodedRequest(RequestKind.RENAME, data.roomId(), data.newName());
    }

    private static Data readData(JsonParser parser) throws IOException {
        Long roomId = null; String newName = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            if ("roomId".equals(field) && value == JsonToken.VALUE_NUMBER_INT)
                roomId = parser.getLongValue();
            else if ("newName".equals(field) && value == JsonToken.VALUE_STRING)
                newName = parser.getText();
            else { invalid = true; parser.skipChildren(); }
        }
        return invalid || roomId == null || newName == null
                ? null : new Data(roomId, newName);
    }

    public byte[] encodeResponse(LegacyV1RoomRenameResult result, long roomId) {
        return encode("RENAME_ROOM_RSP", generator -> {
            generator.writeNumberField("roomId", roomId);
            generator.writeBooleanField("success", result instanceof LegacyV1RoomRenameResult.Renamed);
            if (result instanceof LegacyV1RoomRenameResult.Renamed renamed) {
                generator.writeStringField("newName", renamed.newName());
                generator.writeBooleanField("changed", renamed.changed());
                generator.writeNumberField("updatedAt", renamed.updatedAt().toEpochMilli());
            } else {
                var rejected = (LegacyV1RoomRenameResult.Rejected) result;
                generator.writeStringField("errorCode", switch (rejected) {
                    case INVALID_INPUT -> "INVALID_ROOM_NAME";
                    case ROOM_ADMIN_REQUIRED -> "ROOM_ADMIN_REQUIRED";
                });
                generator.writeStringField("error", switch (rejected) {
                    case INVALID_INPUT -> "房间名称无效";
                    case ROOM_ADMIN_REQUIRED -> "只有管理员可以修改房间名称";
                });
            }
        });
    }

    public Notifications encodeNotifications(
            LegacyV1RoomRenameResult.Renamed renamed, String operator) {
        byte[] notification = encode("RENAME_ROOM_NOTIFY", generator -> {
            generator.writeNumberField("roomId", renamed.legacyRoomId());
            generator.writeStringField("newName", renamed.newName());
        });
        byte[] system = encode("SYSTEM_MSG", generator -> {
            generator.writeNumberField("roomId", renamed.legacyRoomId());
            generator.writeStringField("content", "管理员 " + operator
                    + " 将聊天室名称修改为 \"" + renamed.newName() + "\"");
        });
        return new Notifications(notification, system);
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
        byte[] encoded = output.toByteArray();
        if (encoded.length > 4096) throw new IllegalStateException("V1 rename frame too large");
        return encoded;
    }
    private static DecodedRequest owned(String type) {
        return "RENAME_ROOM_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED, 0, null);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, 0, null);
    }
    private record Data(long roomId, String newName) { }
    @FunctionalInterface private interface Fields {
        void write(JsonGenerator generator) throws IOException;
    }
}
