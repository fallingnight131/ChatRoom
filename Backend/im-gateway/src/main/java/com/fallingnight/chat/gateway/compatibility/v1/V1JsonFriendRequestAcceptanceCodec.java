package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded codec for V1 FRIEND_ACCEPT_REQ/RSP/NOTIFY. */
public final class V1JsonFriendRequestAcceptanceCodec {
    public enum RequestKind { ACCEPT, MALFORMED_ACCEPT, OTHER }
    public record DecodedRequest(RequestKind kind, long requestId) { }

    private static final String ERROR = "\u5904\u7406\u597d\u53cb\u8bf7\u6c42\u5931\u8d25";
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private final Clock clock;

    public V1JsonFriendRequestAcceptanceCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        Objects.requireNonNull(wire, "wire");
        if (wire.length == 0 || wire.length > 4096) return other();
        String type = null;
        Long requestId = null;
        boolean validData = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfAccept(type);
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field)) {
                    if (value == JsonToken.START_OBJECT) {
                        requestId = readData(parser);
                        validData = requestId != null;
                    } else {
                        parser.skipChildren();
                        validData = false;
                    }
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return malformedIfAccept(type);
        } catch (IOException | RuntimeException exception) {
            return malformedIfAccept(type);
        }
        if (!"FRIEND_ACCEPT_REQ".equals(type)) return other();
        if (!validData || requestId == null || requestId <= 0
                || requestId > Integer.MAX_VALUE) return malformed();
        return new DecodedRequest(RequestKind.ACCEPT, requestId);
    }

    private static Long readData(JsonParser parser) throws IOException {
        Long requestId = null;
        boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if ("requestId".equals(field) && value == JsonToken.VALUE_NUMBER_INT) {
                requestId = parser.getLongValue();
            } else if ("fromUsername".equals(field) && value == JsonToken.VALUE_STRING) {
                parser.getText(); // Legacy hint is validated but never trusted for identity.
            } else {
                invalid = true;
                parser.skipChildren();
            }
        }
        return invalid ? null : requestId;
    }

    public byte[] encodeResponse(boolean success) {
        return encode("FRIEND_ACCEPT_RSP", generator -> {
            generator.writeBooleanField("success", success);
            if (!success) generator.writeStringField("error", ERROR);
        });
    }

    public byte[] encodeNotification(String acceptedBy, String acceptedByDisplay) {
        Objects.requireNonNull(acceptedBy, "acceptedBy");
        Objects.requireNonNull(acceptedByDisplay, "acceptedByDisplay");
        return encode("FRIEND_ACCEPT_NOTIFY", generator -> {
            generator.writeStringField("acceptedBy", acceptedBy);
            generator.writeStringField("acceptedByDisplay", acceptedByDisplay);
        });
    }

    private byte[] encode(String type, JsonFields fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(256);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeStringField("type", type);
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            fields.write(generator);
            generator.writeEndObject();
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 friend acceptance encoding failed", exception);
        }
        return output.toByteArray();
    }

    private static DecodedRequest malformedIfAccept(String type) {
        return "FRIEND_ACCEPT_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_ACCEPT, 0);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, 0);
    }

    @FunctionalInterface
    private interface JsonFields { void write(JsonGenerator generator) throws IOException; }
}
