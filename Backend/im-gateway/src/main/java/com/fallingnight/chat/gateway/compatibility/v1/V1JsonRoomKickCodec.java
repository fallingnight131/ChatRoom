package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomKickResult;
import java.io.*;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded codec for V1 KICK_USER request, response, and live effects. */
public final class V1JsonRoomKickCodec {
    public static final int MAX_REQUEST_BYTES = 4096;
    public enum RequestKind { KICK, MALFORMED, OTHER }
    public record DecodedRequest(RequestKind kind, long roomId, String targetUsername) { }
    public record RemainingMemberEffects(byte[] userLeft, byte[] systemMessage) { }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                    .maxStringLength(1024).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;

    public V1JsonRoomKickCodec(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > MAX_REQUEST_BYTES) return other();
        String type = null; Data data = null;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return owned(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field) && value == JsonToken.VALUE_STRING) type = parser.getText();
                else if ("data".equals(field) && value == JsonToken.START_OBJECT) data = data(parser);
                else parser.skipChildren();
            }
            if (parser.nextToken() != null) return owned(type);
        } catch (IOException | RuntimeException exception) { return owned(type); }
        if (!"KICK_USER_REQ".equals(type)) return other();
        return data == null ? malformed()
                : new DecodedRequest(RequestKind.KICK, data.roomId(), data.username());
    }

    private static Data data(JsonParser parser) throws IOException {
        Long room = null; String username = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            if ("roomId".equals(field) && value == JsonToken.VALUE_NUMBER_INT)
                room = parser.getLongValue();
            else if ("username".equals(field) && value == JsonToken.VALUE_STRING)
                username = parser.getText();
            else { invalid = true; parser.skipChildren(); }
        }
        return invalid || room == null || username == null ? null : new Data(room, username);
    }

    public byte[] response(LegacyV1RoomKickResult result, long room, String username) {
        return encode("KICK_USER_RSP", generator -> {
            generator.writeNumberField("roomId", room);
            generator.writeStringField("username", username == null ? "" : username);
            generator.writeBooleanField("success", result instanceof LegacyV1RoomKickResult.Kicked);
            if (result instanceof LegacyV1RoomKickResult.Kicked kicked) {
                generator.writeBooleanField("changed", kicked.changed());
                generator.writeNumberField("kickedAt", kicked.kickedAt().toEpochMilli());
            } else {
                var rejected = (LegacyV1RoomKickResult.Rejected) result;
                generator.writeStringField("errorCode", rejected.name());
                generator.writeStringField("error", "无法踢出该用户");
            }
        });
    }

    public byte[] targetNotification(LegacyV1RoomKickResult.Kicked kicked, String operator) {
        return encode("KICK_USER_NOTIFY", generator -> {
            generator.writeNumberField("roomId", kicked.legacyRoomId());
            generator.writeStringField("roomName", kicked.roomName());
            generator.writeStringField("operator", operator);
        });
    }

    public RemainingMemberEffects remainingMemberEffects(
            LegacyV1RoomKickResult.Kicked kicked, String operator) {
        byte[] left = encode("USER_LEFT", generator -> {
            generator.writeNumberField("roomId", kicked.legacyRoomId());
            generator.writeStringField("username", kicked.targetUsername());
            generator.writeStringField("displayName", kicked.targetDisplayName());
        });
        byte[] system = encode("SYSTEM_MSG", generator -> {
            generator.writeNumberField("roomId", kicked.legacyRoomId());
            generator.writeStringField("content", "管理员 " + operator + " 将 "
                    + kicked.targetDisplayName() + " 踢出了聊天室");
        });
        return new RemainingMemberEffects(left, system);
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
        return "KICK_USER_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED, 0, null);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, 0, null);
    }
    private record Data(long roomId, String username) { }
    @FunctionalInterface private interface Fields { void write(JsonGenerator value) throws IOException; }
}
