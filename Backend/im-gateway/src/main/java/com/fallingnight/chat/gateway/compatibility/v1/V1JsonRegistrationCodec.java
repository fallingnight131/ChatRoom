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

/** Strict V1 registration JSON boundary with clearable password ownership. */
public final class V1JsonRegistrationCodec {
    public static final int MAX_REQUEST_BYTES = 12_288;
    public enum RequestKind { REGISTER, MALFORMED, OTHER }
    public static final class DecodedRequest implements AutoCloseable {
        private final RequestKind kind; private final String username, displayName;
        private byte[] password;
        private DecodedRequest(RequestKind kind, String username, String displayName, byte[] password) {
            this.kind = kind; this.username = username; this.displayName = displayName;
            this.password = password;
        }
        public RequestKind kind() { return kind; }
        public String username() { return username; }
        public LegacyV1RegistrationCommand toCommand() {
            if (kind != RequestKind.REGISTER || password == null)
                throw new IllegalStateException("registration password unavailable");
            return new LegacyV1RegistrationCommand(username, displayName, password);
        }
        @Override public void close() {
            if (password != null) { Arrays.fill(password, (byte) 0); password = null; }
        }
    }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(4096).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;
    public V1JsonRegistrationCodec(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > MAX_REQUEST_BYTES) return other();
        String type = null, username = null, displayName = null; byte[] password = null;
        boolean dataSeen = false, invalid = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return owned(type, password);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return owned(type, password);
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    dataSeen = true;
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        if (parser.currentToken() != JsonToken.FIELD_NAME) return owned(type, password);
                        String dataField = parser.currentName(); JsonToken dataValue = parser.nextToken();
                        if ("username".equals(dataField) && dataValue == JsonToken.VALUE_STRING)
                            username = parser.getText();
                        else if ("displayName".equals(dataField) && dataValue == JsonToken.VALUE_STRING)
                            displayName = parser.getText();
                        else if ("password".equals(dataField) && dataValue == JsonToken.VALUE_STRING) {
                            if (password != null) Arrays.fill(password, (byte) 0);
                            password = utf8(parser);
                        } else { invalid = true; parser.skipChildren(); }
                    }
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return owned(type, password);
        } catch (IOException | RuntimeException exception) { return owned(type, password); }
        if (!"REGISTER_REQ".equals(type)) { clear(password); return other(); }
        if (!dataSeen || invalid || username == null || displayName == null || password == null) {
            clear(password); return malformed();
        }
        return new DecodedRequest(RequestKind.REGISTER, username, displayName, password);
    }
    private static byte[] utf8(JsonParser parser) throws IOException {
        char[] chars = Arrays.copyOfRange(parser.getTextCharacters(), parser.getTextOffset(),
                parser.getTextOffset() + parser.getTextLength()); ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(chars));
            byte[] result = new byte[encoded.remaining()]; encoded.get(result); return result;
        } finally {
            Arrays.fill(chars, '\0');
            if (encoded != null && encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }
    public byte[] encode(LegacyV1RegistrationResult result) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", "REGISTER_RSP");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis()); generator.writeObjectFieldStart("data");
            generator.writeBooleanField("success", result instanceof LegacyV1RegistrationResult.Registered);
            if (result instanceof LegacyV1RegistrationResult.Registered registered) {
                generator.writeNumberField("userId", registered.legacyUserId());
                generator.writeStringField("username", registered.username());
                generator.writeBooleanField("duplicate", registered.duplicate());
            } else {
                var rejected = (LegacyV1RegistrationResult.Rejected) result;
                generator.writeStringField("errorCode", rejected.name());
                generator.writeStringField("error", rejected == LegacyV1RegistrationResult.Rejected.INVALID_INPUT
                        ? "注册信息格式无效" : "用户ID已存在或注册暂不可用");
            }
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
        return output.toByteArray();
    }
    private static DecodedRequest owned(String type, byte[] password) {
        clear(password); return "REGISTER_REQ".equals(type) ? malformed() : other();
    }
    private static void clear(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED, null, null, null);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, null, null, null);
    }
}
