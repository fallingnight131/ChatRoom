package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomCreationResult;
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

/** Strict bounded V1 CREATE_ROOM_REQ/RSP codec with clearable password bytes. */
public final class V1JsonRoomCreationCodec {
    public enum RequestKind { CREATE, MALFORMED_CREATE, OTHER }
    public static final class DecodedRequest implements AutoCloseable {
        private final RequestKind kind;
        private final String clientRequestId;
        private final String roomName;
        private byte[] passwordUtf8;
        private DecodedRequest(RequestKind kind, String clientRequestId,
                String roomName, byte[] passwordUtf8) {
            this.kind = kind; this.clientRequestId = clientRequestId;
            this.roomName = roomName; this.passwordUtf8 = passwordUtf8;
        }
        public RequestKind kind() { return kind; }
        public String clientRequestId() { return clientRequestId; }
        public String roomName() { return roomName; }
        public byte[] passwordCopy() {
            if (passwordUtf8 == null) return null;
            return passwordUtf8.clone();
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
    public V1JsonRoomCreationCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > 8192) return other();
        String type = null, envelopeId = null; Data data = null;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    clear(data); return malformedIfCreate(type);
                }
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) { clear(data); return other(); }
                    type = parser.getText();
                } else if ("id".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) {
                        clear(data); return malformedIfCreate(type);
                    }
                    envelopeId = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    data = readData(parser);
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) {
                clear(data); return malformedIfCreate(type);
            }
        } catch (IOException | RuntimeException exception) {
            clear(data); return malformedIfCreate(type);
        }
        if (!"CREATE_ROOM_REQ".equals(type)) { clear(data); return other(); }
        if (envelopeId == null || data == null || data.roomName() == null) {
            clear(data); return malformed();
        }
        byte[] password = data.passwordUtf8();
        return new DecodedRequest(RequestKind.CREATE, envelopeId, data.roomName(), password);
    }

    private static Data readData(JsonParser parser) throws IOException {
        String name = null; byte[] password = null; boolean invalid = false;
        try {
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("roomName".equals(field) && value == JsonToken.VALUE_STRING) {
                    name = parser.getText();
                } else if ("password".equals(field) && value == JsonToken.VALUE_STRING) {
                    if (password != null) Arrays.fill(password, (byte) 0);
                    password = utf8Text(parser);
                } else { invalid = true; parser.skipChildren(); }
            }
            if (invalid) return null;
            if (password == null || password.length == 0) return new Data(name, clearToNull(password));
            byte[] transferredPassword = password;
            password = null;
            return new Data(name, transferredPassword);
        } finally {
            if (password != null) Arrays.fill(password, (byte) 0);
        }
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
        } catch (CharacterCodingException exception) {
            throw new IOException("password is not valid UTF-8 text", exception);
        } finally {
            Arrays.fill(characters, '\0');
            if (encoded != null && encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }

    private static byte[] clearToNull(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0); return null;
    }
    private static void clear(Data data) {
        if (data != null && data.passwordUtf8() != null) {
            Arrays.fill(data.passwordUtf8(), (byte) 0);
        }
    }

    public byte[] encode(LegacyV1RoomCreationResult result) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", "CREATE_ROOM_RSP");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            if (result instanceof LegacyV1RoomCreationResult.Created created) {
                generator.writeBooleanField("success", true);
                generator.writeNumberField("roomId", created.legacyRoomId());
                generator.writeStringField("roomName", created.roomName());
                generator.writeBooleanField("isAdmin", true);
                generator.writeBooleanField("duplicate", created.duplicate());
            } else {
                var rejected = (LegacyV1RoomCreationResult.Rejected) result;
                generator.writeBooleanField("success", false);
                generator.writeStringField("errorCode", rejected.name());
                generator.writeStringField("error", switch (rejected) {
                    case INVALID_INPUT -> "聊天室名称、密码或请求标识无效";
                    case CREATION_DENIED -> "无法创建聊天室";
                    case CLIENT_REQUEST_ID_CONFLICT -> "请求标识已用于不同聊天室";
                });
            }
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 room creation encoding failed", exception);
        }
        return output.toByteArray();
    }

    private static DecodedRequest malformedIfCreate(String type) {
        return "CREATE_ROOM_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_CREATE, null, null, null);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, null, null, null);
    }
    private record Data(String roomName, byte[] passwordUtf8) { }
}
