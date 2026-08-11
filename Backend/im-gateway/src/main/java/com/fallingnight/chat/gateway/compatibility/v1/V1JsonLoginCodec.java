package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Bounded streaming JSON codec for only the V1 login compatibility slice. */
public final class V1JsonLoginCodec {
    public static final int MAX_LOGIN_WIRE_BYTES = 16 * 1024;
    public static final int MAX_USERNAME_CHARS = 20;
    public static final int MAX_PASSWORD_CHARS = 1024;
    private static final String GENERIC_REJECTION = "用户ID或密码错误";

    private final JsonFactory json;
    private final Clock clock;

    public V1JsonLoginCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        json = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(8)
                        .maxStringLength(4096)
                        .maxNumberLength(32)
                        .build())
                .streamWriteConstraints(StreamWriteConstraints.builder()
                        .maxNestingDepth(8)
                        .build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
                .build();
    }

    public DecodedV1Login decode(byte[] wire) {
        Objects.requireNonNull(wire, "wire");
        if (wire.length == 0 || wire.length > MAX_LOGIN_WIRE_BYTES) {
            throw invalid();
        }
        String type = null;
        String username = null;
        String password = null;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw invalid();
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken() == JsonToken.FIELD_NAME);
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    require(value == JsonToken.VALUE_STRING);
                    type = parser.getText();
                } else if ("data".equals(field)) {
                    require(value == JsonToken.START_OBJECT);
                    String[] credentials = readCredentials(parser);
                    username = credentials[0];
                    password = credentials[1];
                } else {
                    parser.skipChildren();
                }
            }
            require(parser.nextToken() == null);
        } catch (IOException | RuntimeException exception) {
            throw invalid();
        }
        require("LOGIN_REQ".equals(type));
        require(username != null && !username.isEmpty()
                && username.length() <= MAX_USERNAME_CHARS);
        require(password != null && password.length() <= MAX_PASSWORD_CHARS);
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        try {
            return new DecodedV1Login(username, passwordBytes);
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
        }
    }

    public byte[] encodeEstablished(LegacyV1AuthenticatedIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return encode(generator -> {
            generator.writeBooleanField("success", true);
            generator.writeNumberField("userId", identity.legacyUserId());
            generator.writeStringField("username", identity.username());
            generator.writeStringField("displayName", identity.displayName());
        });
    }

    public byte[] encodeRejected() {
        return encode(generator -> {
            generator.writeBooleanField("success", false);
            generator.writeStringField("error", GENERIC_REJECTION);
        });
    }

    private String[] readCredentials(JsonParser parser) throws IOException {
        String username = null;
        String password = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            require(parser.currentToken() == JsonToken.FIELD_NAME);
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if ("username".equals(field)) {
                require(value == JsonToken.VALUE_STRING);
                username = parser.getText();
            } else if ("password".equals(field)) {
                require(value == JsonToken.VALUE_STRING);
                password = parser.getText();
            } else {
                parser.skipChildren();
            }
        }
        return new String[] {username, password};
    }

    private byte[] encode(DataWriter writer) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(256);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeStringField("type", "LOGIN_RSP");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            writer.write(generator);
            generator.writeEndObject();
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 login response encoding failed", exception);
        }
        byte[] encoded = output.toByteArray();
        if (encoded.length > MAX_LOGIN_WIRE_BYTES) {
            throw new IllegalStateException("V1 login response exceeded its fixed bound");
        }
        return encoded;
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid V1 login envelope");
    }

    @FunctionalInterface
    private interface DataWriter {
        void write(JsonGenerator generator) throws IOException;
    }
}
