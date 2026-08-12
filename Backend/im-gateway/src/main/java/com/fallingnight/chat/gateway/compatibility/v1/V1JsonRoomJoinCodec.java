package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomJoinResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded V1 JOIN_ROOM_REQ/RSP codec with owned password bytes. */
public final class V1JsonRoomJoinCodec {
    public enum RequestKind { JOIN, MALFORMED_JOIN, OTHER }

    public static final class DecodedRequest implements AutoCloseable {
        private final RequestKind kind;
        private final long roomId;
        private byte[] passwordUtf8;
        private DecodedRequest(RequestKind kind, long roomId, byte[] passwordUtf8) {
            this.kind = kind; this.roomId = roomId; this.passwordUtf8 = passwordUtf8;
        }
        public RequestKind kind() { return kind; }
        public long roomId() { return roomId; }
        public boolean hasPassword() { return passwordUtf8 != null; }
        public byte[] passwordCopy() {
            return passwordUtf8 == null ? null : passwordUtf8.clone();
        }
        @Override public void close() {
            if (passwordUtf8 != null) {
                Arrays.fill(passwordUtf8, (byte) 0); passwordUtf8 = null;
            }
        }
    }

    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(4096).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;

    public V1JsonRoomJoinCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > 8192) return other();
        String type = null; Data data = null;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    clear(data); return malformedIfJoin(type);
                }
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) { clear(data); return other(); }
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    data = readData(parser);
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) { clear(data); return malformedIfJoin(type); }
        } catch (IOException | RuntimeException exception) {
            clear(data); return malformedIfJoin(type);
        }
        if (!"JOIN_ROOM_REQ".equals(type)) { clear(data); return other(); }
        if (data == null || data.roomId() == null) { clear(data); return malformed(); }
        byte[] password = data.passwordUtf8();
        return new DecodedRequest(RequestKind.JOIN, data.roomId(), password);
    }

    private static Data readData(JsonParser parser) throws IOException {
        Long roomId = null; byte[] password = null; boolean invalid = false;
        try {
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("roomId".equals(field) && value == JsonToken.VALUE_NUMBER_INT) {
                    roomId = parser.getLongValue();
                } else if ("password".equals(field) && value == JsonToken.VALUE_STRING) {
                    if (password != null) Arrays.fill(password, (byte) 0);
                    password = utf8Text(parser);
                } else { invalid = true; parser.skipChildren(); }
            }
            if (invalid) return null;
            if (password == null || password.length == 0) {
                clear(password); return new Data(roomId, null);
            }
            byte[] transferred = password; password = null;
            return new Data(roomId, transferred);
        } finally { clear(password); }
    }

    private static byte[] utf8Text(JsonParser parser) throws IOException {
        char[] characters = Arrays.copyOfRange(parser.getTextCharacters(),
                parser.getTextOffset(), parser.getTextOffset() + parser.getTextLength());
        ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(characters));
            byte[] result = new byte[encoded.remaining()]; encoded.get(result); return result;
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IOException("room password is not valid UTF-8", exception);
        } finally {
            Arrays.fill(characters, '\0');
            if (encoded != null && encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }

    public byte[] encodeRateLimited(long roomId, long retryAfterMs) {
        return encode(generator -> {
            generator.writeBooleanField("success", false);
            generator.writeNumberField("roomId", roomId);
            generator.writeBooleanField("needPassword", true);
            generator.writeStringField("errorCode", "RATE_LIMITED");
            generator.writeStringField("error", "密码尝试过于频繁，请稍后再试");
            generator.writeNumberField("retryAfterMs", retryAfterMs);
        }, "JOIN_ROOM_RSP");
    }

    public byte[] encodeResponse(LegacyV1RoomJoinResult result, long requestedRoomId) {
        Objects.requireNonNull(result, "result");
        return encode(generator -> {
            if (result instanceof LegacyV1RoomJoinResult.Joined joined) {
                generator.writeBooleanField("success", true);
                generator.writeNumberField("roomId", joined.legacyRoomId());
                generator.writeStringField("roomName", joined.roomName());
                generator.writeBooleanField("isAdmin",
                        joined.role() != LegacyV1RoomJoinResult.Role.MEMBER);
                generator.writeBooleanField("newJoin", joined.newJoin());
                return;
            }
            var rejected = (LegacyV1RoomJoinResult.Rejected) result;
            generator.writeBooleanField("success", false);
            if (requestedRoomId > 0 && requestedRoomId <= Integer.MAX_VALUE) {
                generator.writeNumberField("roomId", requestedRoomId);
            }
            generator.writeStringField("errorCode", rejected.name());
            switch (rejected) {
                case PASSWORD_REQUIRED, INVALID_PASSWORD -> {
                    generator.writeBooleanField("needPassword", true);
                    generator.writeStringField("error", rejected ==
                            LegacyV1RoomJoinResult.Rejected.PASSWORD_REQUIRED
                            ? "该聊天室需要密码才能加入" : "密码错误");
                }
                case INVALID_INPUT -> generator.writeStringField("error", "加入房间请求无效");
                case NOT_FOUND -> generator.writeStringField("error", "房间不存在");
                case ROOM_FULL -> generator.writeStringField("error", "聊天室人数已达上限");
                case JOIN_DENIED -> generator.writeStringField("error", "无法加入聊天室");
                case ACCESS_CHANGED -> generator.writeStringField("error", "聊天室访问策略已变化，请重试");
            }
        }, "JOIN_ROOM_RSP");
    }

    public byte[] encodeNotification(LegacyV1RoomJoinResult.Joined joined,
            String username, String displayName) {
        Objects.requireNonNull(joined, "joined");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(displayName, "displayName");
        return encode(generator -> {
            generator.writeNumberField("roomId", joined.legacyRoomId());
            generator.writeStringField("username", username);
            generator.writeStringField("displayName", displayName);
        }, "USER_JOINED");
    }

    private byte[] encode(Writer writer, String type) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(384);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", type);
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data"); writer.write(generator);
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 room join encoding failed", exception);
        }
        return output.toByteArray();
    }

    private static void clear(Data data) {
        if (data != null) clear(data.passwordUtf8());
    }
    private static void clear(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }
    private static DecodedRequest malformedIfJoin(String type) {
        return "JOIN_ROOM_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_JOIN, 0, null);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, 0, null);
    }
    private record Data(Long roomId, byte[] passwordUtf8) { }
    @FunctionalInterface private interface Writer { void write(JsonGenerator generator) throws IOException; }
}
