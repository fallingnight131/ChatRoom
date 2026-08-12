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

/** Strict bounded codec for V1 FRIEND_REJECT_REQ/RSP. */
public final class V1JsonFriendRequestRejectionCodec {
    public enum RequestKind { REJECT, MALFORMED_REJECT, OTHER }

    public record DecodedRequest(RequestKind kind, long requestId) {
        public DecodedRequest {
            Objects.requireNonNull(kind, "kind");
            if (kind != RequestKind.REJECT && requestId != 0) {
                throw new IllegalArgumentException("non-reject request has an ID");
            }
        }
    }

    private static final int MAX_REQUEST_WIRE_BYTES = 4 * 1024;
    private static final String ERROR = "\u5904\u7406\u597d\u53cb\u8bf7\u6c42\u5931\u8d25";
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private final Clock clock;

    public V1JsonFriendRequestRejectionCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        Objects.requireNonNull(wire, "wire");
        if (wire.length == 0 || wire.length > MAX_REQUEST_WIRE_BYTES) return other();
        String type = null;
        Long requestId = null;
        boolean validData = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfReject(type);
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field)) {
                    if (value != JsonToken.START_OBJECT) {
                        parser.skipChildren();
                        validData = false;
                    } else {
                        requestId = readData(parser);
                        validData = requestId != null;
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) return malformedIfReject(type);
        } catch (IOException | RuntimeException exception) {
            return malformedIfReject(type);
        }
        if (!"FRIEND_REJECT_REQ".equals(type)) return other();
        if (!validData || requestId == null || requestId <= 0
                || requestId > Integer.MAX_VALUE) return malformed();
        return new DecodedRequest(RequestKind.REJECT, requestId);
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
            } else {
                invalid = true;
                parser.skipChildren();
            }
        }
        return invalid ? null : requestId;
    }

    public byte[] encode(boolean success) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(256);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeStringField("type", "FRIEND_REJECT_RSP");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            generator.writeBooleanField("success", success);
            if (!success) generator.writeStringField("error", ERROR);
            generator.writeEndObject();
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 friend rejection encoding failed", exception);
        }
        return output.toByteArray();
    }

    private static DecodedRequest malformedIfReject(String type) {
        return "FRIEND_REJECT_REQ".equals(type) ? malformed() : other();
    }

    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_REJECT, 0);
    }

    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, 0);
    }
}
