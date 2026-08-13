package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomDissolutionResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded V1 room-dissolution translation with authoritative output. */
public final class V1JsonRoomDissolutionCodec {
    public enum RequestKind { DISSOLVE, MALFORMED, OTHER }
    public record DecodedRequest(RequestKind kind, long roomId) { }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(256).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;

    public V1JsonRoomDissolutionCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > 4096) return other();
        String type = null; Long roomId = null; boolean dataSeen = false, invalid = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return owned(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    dataSeen = true;
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        if (parser.currentToken() != JsonToken.FIELD_NAME) return owned(type);
                        String dataField = parser.currentName(); JsonToken dataValue = parser.nextToken();
                        if ("roomId".equals(dataField) && dataValue == JsonToken.VALUE_NUMBER_INT)
                            roomId = parser.getLongValue();
                        else if ("roomName".equals(dataField)
                                && dataValue == JsonToken.VALUE_STRING) parser.getText();
                        else { invalid = true; parser.skipChildren(); }
                    }
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return owned(type);
        } catch (IOException | RuntimeException exception) { return owned(type); }
        if (!"DELETE_ROOM_REQ".equals(type)) return other();
        return !dataSeen || invalid || roomId == null
                ? malformed() : new DecodedRequest(RequestKind.DISSOLVE, roomId);
    }

    public byte[] encodeResponse(LegacyV1RoomDissolutionResult result, long roomId) {
        return encode("DELETE_ROOM_RSP", generator -> {
            generator.writeNumberField("roomId", roomId);
            generator.writeBooleanField("success",
                    result instanceof LegacyV1RoomDissolutionResult.Dissolved);
            if (result instanceof LegacyV1RoomDissolutionResult.Dissolved dissolved) {
                generator.writeStringField("roomName", dissolved.roomName());
                generator.writeBooleanField("changed", dissolved.changed());
                generator.writeNumberField("dissolvedAt", dissolved.dissolvedAt().toEpochMilli());
            } else {
                var rejected = (LegacyV1RoomDissolutionResult.Rejected) result;
                generator.writeStringField("errorCode", rejected.name());
                generator.writeStringField("error", switch (rejected) {
                    case INVALID_INPUT -> "聊天室标识无效";
                    case ROOM_ADMIN_REQUIRED -> "您没有管理员权限";
                    case NOT_FOUND -> "聊天室不存在";
                });
            }
        });
    }

    public byte[] encodeNotification(LegacyV1RoomDissolutionResult.Dissolved dissolved,
            String operator) {
        return encode("DELETE_ROOM_NOTIFY", generator -> {
            generator.writeNumberField("roomId", dissolved.legacyRoomId());
            generator.writeStringField("roomName", dissolved.roomName());
            generator.writeStringField("operator", operator);
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
    private static DecodedRequest owned(String type) {
        return "DELETE_ROOM_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED, 0);
    }
    private static DecodedRequest other() { return new DecodedRequest(RequestKind.OTHER, 0); }
    @FunctionalInterface private interface Fields {
        void write(JsonGenerator generator) throws IOException;
    }
}
