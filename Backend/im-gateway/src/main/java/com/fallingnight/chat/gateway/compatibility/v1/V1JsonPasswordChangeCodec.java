package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.*;
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

/** Strict V1 password-change codec with explicit clearable secret ownership. */
public final class V1JsonPasswordChangeCodec {
    public static final int MAX_REQUEST_BYTES = 16_384;
    public enum RequestKind { CHANGE, MALFORMED, OTHER }
    public static final class DecodedRequest implements AutoCloseable {
        private final RequestKind kind; private byte[] oldPassword; private byte[] newPassword;
        private DecodedRequest(RequestKind kind, byte[] oldPassword, byte[] newPassword) {
            this.kind = kind; this.oldPassword = oldPassword; this.newPassword = newPassword;
        }
        public RequestKind kind() { return kind; }
        public LegacyV1PasswordChangeCommand toCommand(UUID accountId, UUID sessionId) {
            if (kind != RequestKind.CHANGE || oldPassword == null || newPassword == null)
                throw new IllegalStateException("password change secrets unavailable");
            return new LegacyV1PasswordChangeCommand(accountId, sessionId, oldPassword, newPassword);
        }
        public boolean isClosed() { return oldPassword == null && newPassword == null; }
        @Override public void close() {
            if (oldPassword != null) { Arrays.fill(oldPassword, (byte) 0); oldPassword = null; }
            if (newPassword != null) { Arrays.fill(newPassword, (byte) 0); newPassword = null; }
        }
    }

    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(4096).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;
    public V1JsonPasswordChangeCodec(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > MAX_REQUEST_BYTES) return other();
        String type = null; Secrets secrets = null;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) { clear(secrets); return owned(type); }
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) { clear(secrets); return other(); }
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    clear(secrets); secrets = readSecrets(parser);
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) { clear(secrets); return owned(type); }
        } catch (IOException | RuntimeException exception) { clear(secrets); return owned(type); }
        if (!"CHANGE_PASSWORD_REQ".equals(type)) { clear(secrets); return other(); }
        if (secrets == null || secrets.oldPassword() == null || secrets.newPassword() == null) {
            clear(secrets); return malformed();
        }
        byte[] oldValue = secrets.oldPassword(), newValue = secrets.newPassword();
        secrets = null; return new DecodedRequest(RequestKind.CHANGE, oldValue, newValue);
    }

    private static Secrets readSecrets(JsonParser parser) throws IOException {
        byte[] oldValue = null, newValue = null; boolean invalid = false;
        try {
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("oldPassword".equals(field) && value == JsonToken.VALUE_STRING)
                    oldValue = replace(oldValue, utf8(parser));
                else if ("newPassword".equals(field) && value == JsonToken.VALUE_STRING)
                    newValue = replace(newValue, utf8(parser));
                else { invalid = true; parser.skipChildren(); }
            }
            if (invalid) return null;
            Secrets result = new Secrets(oldValue, newValue); oldValue = null; newValue = null;
            return result;
        } finally {
            if (oldValue != null) Arrays.fill(oldValue, (byte) 0);
            if (newValue != null) Arrays.fill(newValue, (byte) 0);
        }
    }
    private static byte[] replace(byte[] previous, byte[] replacement) {
        if (previous != null) Arrays.fill(previous, (byte) 0); return replacement;
    }
    private static byte[] utf8(JsonParser parser) throws IOException {
        char[] chars = Arrays.copyOfRange(parser.getTextCharacters(), parser.getTextOffset(),
                parser.getTextOffset() + parser.getTextLength()); ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(chars));
            byte[] result = new byte[encoded.remaining()]; encoded.get(result); return result;
        } finally {
            Arrays.fill(chars, '\0');
            if (encoded != null && encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }

    public byte[] encode(LegacyV1PasswordChangeResult result) {
        return response(generator -> {
            generator.writeBooleanField("success",
                    result instanceof LegacyV1PasswordChangeResult.Changed);
            if (result instanceof LegacyV1PasswordChangeResult.Changed changed) {
                generator.writeBooleanField("changed", changed.changed());
                generator.writeNumberField("otherSessionsRevoked", changed.otherSessionsRevoked());
                generator.writeNumberField("changedAt", changed.changedAt().toEpochMilli());
            } else {
                var rejected = (LegacyV1PasswordChangeResult.Rejected) result;
                generator.writeStringField("errorCode", rejected.name());
                generator.writeStringField("error", rejected ==
                        LegacyV1PasswordChangeResult.Rejected.INVALID_INPUT
                        ? "密码格式无效" : "旧密码不正确或会话已失效");
            }
        });
    }
    public byte[] encodeAdmissionDenied() {
        return response(generator -> {
            generator.writeBooleanField("success", false);
            generator.writeStringField("errorCode", "RATE_LIMITED");
            generator.writeStringField("error", "请求过于频繁，请稍后重试");
        });
    }
    private byte[] response(Fields fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", "CHANGE_PASSWORD_RSP");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data"); fields.write(generator);
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
        return output.toByteArray();
    }
    private static DecodedRequest owned(String type) {
        return "CHANGE_PASSWORD_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED, null, null);
    }
    private static DecodedRequest other() { return new DecodedRequest(RequestKind.OTHER, null, null); }
    private static void clear(Secrets value) {
        if (value != null) {
            if (value.oldPassword() != null) Arrays.fill(value.oldPassword(), (byte) 0);
            if (value.newPassword() != null) Arrays.fill(value.newPassword(), (byte) 0);
        }
    }
    private record Secrets(byte[] oldPassword, byte[] newPassword) { }
    @FunctionalInterface private interface Fields { void write(JsonGenerator generator) throws IOException; }
}
