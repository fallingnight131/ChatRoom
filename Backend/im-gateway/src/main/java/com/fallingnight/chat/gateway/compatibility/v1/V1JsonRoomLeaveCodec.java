package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomLeaveResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded V1 LEAVE_ROOM/RSP and related notification codec. */
public final class V1JsonRoomLeaveCodec {
    public enum RequestKind { LEAVE, MALFORMED_LEAVE, OTHER }
    public record DecodedRequest(RequestKind kind, long roomId) { }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;

    public V1JsonRoomLeaveCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > 4096) return other();
        String type = null; Long roomId = null; boolean validData = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfLeave(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    roomId = readData(parser); validData = roomId != null;
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return malformedIfLeave(type);
        } catch (IOException | RuntimeException exception) {
            return malformedIfLeave(type);
        }
        if (!"LEAVE_ROOM".equals(type)) return other();
        return validData ? new DecodedRequest(RequestKind.LEAVE, roomId) : malformed();
    }

    private static Long readData(JsonParser parser) throws IOException {
        Long roomId = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            if ("roomId".equals(field) && value == JsonToken.VALUE_NUMBER_INT) {
                roomId = parser.getLongValue();
            } else { invalid = true; parser.skipChildren(); }
        }
        return invalid ? null : roomId;
    }

    public byte[] encodeResponse(LegacyV1RoomLeaveResult result, long requestedRoomId) {
        Objects.requireNonNull(result, "result");
        return encode("LEAVE_ROOM_RSP", generator -> {
            if (result instanceof LegacyV1RoomLeaveResult.Left left) {
                generator.writeBooleanField("success", true);
                generator.writeNumberField("roomId", left.legacyRoomId());
                return;
            }
            var rejected = (LegacyV1RoomLeaveResult.Rejected) result;
            generator.writeBooleanField("success", false);
            if (requestedRoomId > 0 && requestedRoomId <= Integer.MAX_VALUE) {
                generator.writeNumberField("roomId", requestedRoomId);
            }
            generator.writeStringField("errorCode", rejected.name());
            generator.writeStringField("error", rejected ==
                    LegacyV1RoomLeaveResult.Rejected.NOT_MEMBER
                    ? "您不在该聊天室中" : "无法退出聊天室");
        });
    }

    public byte[] encodeUserLeft(long roomId, String username, String displayName) {
        return encode("USER_LEFT", generator -> {
            generator.writeNumberField("roomId", roomId);
            generator.writeStringField("username", Objects.requireNonNull(username));
            generator.writeStringField("displayName", Objects.requireNonNull(displayName));
        });
    }

    public byte[] encodeAdminStatus(long roomId) {
        return encode("ADMIN_STATUS", generator -> {
            generator.writeNumberField("roomId", roomId);
            generator.writeBooleanField("isAdmin", true);
        });
    }

    public byte[] encodeOwnershipSystemMessage(long roomId, String successorDisplayName) {
        return encode("SYSTEM_MSG", generator -> {
            generator.writeNumberField("roomId", roomId);
            generator.writeStringField("content", Objects.requireNonNull(successorDisplayName)
                    + " 已被自动指定为管理员");
        });
    }

    private byte[] encode(String type, Writer writer) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(256);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", type);
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data"); writer.write(generator);
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 room leave encoding failed", exception);
        }
        return output.toByteArray();
    }
    private static DecodedRequest malformedIfLeave(String type) {
        return "LEAVE_ROOM".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_LEAVE, 0);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, 0);
    }
    @FunctionalInterface private interface Writer { void write(JsonGenerator value) throws IOException; }
}
