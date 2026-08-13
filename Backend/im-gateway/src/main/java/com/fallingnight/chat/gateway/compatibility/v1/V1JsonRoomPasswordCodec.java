package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Strict V1 room-password codec that owns and clears decoded secret bytes. */
public final class V1JsonRoomPasswordCodec {
    public static final int MAX_REQUEST_BYTES = 8192;
    public enum RequestKind { SET, STATUS, MALFORMED, OTHER }
    public static final class DecodedRequest implements AutoCloseable {
        private final RequestKind kind;
        private final long roomId;
        private byte[] passwordUtf8;
        private DecodedRequest(RequestKind kind, long roomId, byte[] passwordUtf8) {
            this.kind = kind; this.roomId = roomId; this.passwordUtf8 = passwordUtf8;
        }
        public RequestKind kind() { return kind; }
        public long roomId() { return roomId; }
        public byte[] passwordCopy() {
            if (kind != RequestKind.SET || passwordUtf8 == null)
                throw new IllegalStateException("password is unavailable");
            return passwordUtf8.clone();
        }
        public boolean isClosed() { return passwordUtf8 == null; }
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
    public V1JsonRoomPasswordCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > MAX_REQUEST_BYTES) return other();
        String type = null; Data data = null;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    clear(data); return owned(type);
                }
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) { clear(data); return other(); }
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    data = readData(parser);
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) { clear(data); return owned(type); }
        } catch (IOException | RuntimeException exception) {
            clear(data); return owned(type);
        }
        RequestKind kind = kind(type);
        if (kind == RequestKind.OTHER) { clear(data); return other(); }
        if (data == null || data.roomId() == null
                || (kind == RequestKind.SET) != data.passwordSeen()) {
            clear(data); return malformed();
        }
        long roomId = data.roomId();
        byte[] transferred = data.passwordUtf8();
        data = null;
        return new DecodedRequest(kind, roomId, transferred);
    }

    private static Data readData(JsonParser parser) throws IOException {
        Long roomId = null; byte[] password = null; boolean passwordSeen = false, invalid = false;
        try {
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("roomId".equals(field) && value == JsonToken.VALUE_NUMBER_INT)
                    roomId = parser.getLongValue();
                else if ("password".equals(field) && value == JsonToken.VALUE_STRING) {
                    passwordSeen = true;
                    if (password != null) Arrays.fill(password, (byte) 0);
                    password = utf8Text(parser);
                } else { invalid = true; parser.skipChildren(); }
            }
            if (invalid) return null;
            byte[] transferred = password; password = null;
            return new Data(roomId, passwordSeen, transferred);
        } finally { if (password != null) Arrays.fill(password, (byte) 0); }
    }

    private static byte[] utf8Text(JsonParser parser) throws IOException {
        char[] characters = Arrays.copyOfRange(parser.getTextCharacters(), parser.getTextOffset(),
                parser.getTextOffset() + parser.getTextLength());
        ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(characters));
            byte[] result = new byte[encoded.remaining()]; encoded.get(result); return result;
        } catch (CharacterCodingException exception) {
            throw new IOException("room password is not valid UTF-8", exception);
        } finally {
            Arrays.fill(characters, '\0');
            if (encoded != null && encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }

    public byte[] encodeStatus(LegacyV1RoomPasswordStatusResult result, long roomId) {
        return encode("GET_ROOM_PASSWORD_RSP", generator -> {
            generator.writeNumberField("roomId", roomId);
            generator.writeBooleanField("success",
                    result instanceof LegacyV1RoomPasswordStatusResult.Authorized);
            if (result instanceof LegacyV1RoomPasswordStatusResult.Authorized authorized) {
                generator.writeBooleanField("hasPassword", authorized.hasPassword());
                generator.writeNumberField("updatedAt", authorized.updatedAt().toEpochMilli());
            } else writeStatusRejection(generator,
                    (LegacyV1RoomPasswordStatusResult.Rejected) result);
        });
    }

    public byte[] encodeUpdate(LegacyV1RoomPasswordUpdateResult result, long roomId) {
        return encode("SET_ROOM_PASSWORD_RSP", generator -> {
            generator.writeNumberField("roomId", roomId);
            generator.writeBooleanField("success",
                    result instanceof LegacyV1RoomPasswordUpdateResult.Updated);
            if (result instanceof LegacyV1RoomPasswordUpdateResult.Updated updated) {
                generator.writeBooleanField("hasPassword", updated.hasPassword());
                generator.writeBooleanField("changed", updated.changed());
                generator.writeNumberField("updatedAt", updated.updatedAt().toEpochMilli());
            } else writeUpdateRejection(generator,
                    (LegacyV1RoomPasswordUpdateResult.Rejected) result);
        });
    }

    public byte[] encodeSystemMessage(
            LegacyV1RoomPasswordUpdateResult.Updated updated, String operator) {
        return encode("SYSTEM_MSG", generator -> {
            generator.writeNumberField("roomId", updated.legacyRoomId());
            generator.writeStringField("content", updated.hasPassword()
                    ? "管理员 " + operator + " 已设置/修改聊天室密码"
                    : "管理员 " + operator + " 已取消聊天室密码");
        });
    }

    private static void writeStatusRejection(JsonGenerator generator,
            LegacyV1RoomPasswordStatusResult.Rejected rejected) throws IOException {
        generator.writeStringField("errorCode", rejected.name());
        generator.writeStringField("error", rejected ==
                LegacyV1RoomPasswordStatusResult.Rejected.INVALID_INPUT
                ? "聊天室标识无效" : "only admin can query password status");
    }
    private static void writeUpdateRejection(JsonGenerator generator,
            LegacyV1RoomPasswordUpdateResult.Rejected rejected) throws IOException {
        generator.writeStringField("errorCode", rejected.name());
        generator.writeStringField("error", rejected ==
                LegacyV1RoomPasswordUpdateResult.Rejected.INVALID_INPUT
                ? "聊天室密码无效" : "只有管理员可以设置聊天室密码");
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
        if (encoded.length > 4096) throw new IllegalStateException("V1 password frame too large");
        return encoded;
    }

    private static RequestKind kind(String type) {
        if ("SET_ROOM_PASSWORD_REQ".equals(type)) return RequestKind.SET;
        if ("GET_ROOM_PASSWORD_REQ".equals(type)) return RequestKind.STATUS;
        return RequestKind.OTHER;
    }
    private static DecodedRequest owned(String type) {
        return kind(type) == RequestKind.OTHER ? other() : malformed();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED, 0, null);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, 0, null);
    }
    private static void clear(Data data) {
        if (data != null && data.passwordUtf8() != null)
            Arrays.fill(data.passwordUtf8(), (byte) 0);
    }
    private record Data(Long roomId, boolean passwordSeen, byte[] passwordUtf8) { }
    @FunctionalInterface private interface Fields {
        void write(JsonGenerator generator) throws IOException;
    }
}
