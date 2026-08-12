package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestCreationResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded codec for V1 FRIEND_REQUEST_REQ/RSP/NOTIFY. */
public final class V1JsonFriendRequestCreationCodec {
    public enum RequestKind { CREATE, MALFORMED_CREATE, OTHER }
    public record DecodedRequest(RequestKind kind, String username) { }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private final Clock clock;

    public V1JsonFriendRequestCreationCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        Objects.requireNonNull(wire, "wire");
        if (wire.length == 0 || wire.length > 4096) return other();
        String type = null;
        String username = null;
        boolean validData = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfCreate(type);
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field)) {
                    if (value == JsonToken.START_OBJECT) {
                        username = readData(parser);
                        validData = username != null;
                    } else {
                        parser.skipChildren();
                        validData = false;
                    }
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return malformedIfCreate(type);
        } catch (IOException | RuntimeException exception) {
            return malformedIfCreate(type);
        }
        if (!"FRIEND_REQUEST_REQ".equals(type)) return other();
        return validData ? new DecodedRequest(RequestKind.CREATE, username) : malformed();
    }

    private static String readData(JsonParser parser) throws IOException {
        String username = null;
        boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if ("username".equals(field) && value == JsonToken.VALUE_STRING) {
                username = parser.getText();
            } else {
                invalid = true;
                parser.skipChildren();
            }
        }
        return invalid ? null : username;
    }

    public byte[] encodeResponse(LegacyV1FriendRequestCreationResult result) {
        Objects.requireNonNull(result, "result");
        return encode("FRIEND_REQUEST_RSP", generator -> {
            boolean success = result instanceof LegacyV1FriendRequestCreationResult.Accepted;
            generator.writeBooleanField("success", success);
            if (!success) generator.writeStringField("error", errorFor(
                    (LegacyV1FriendRequestCreationResult.Rejected) result));
        });
    }

    public byte[] encodeNotification(String fromUsername, String fromDisplayName) {
        Objects.requireNonNull(fromUsername, "fromUsername");
        Objects.requireNonNull(fromDisplayName, "fromDisplayName");
        return encode("FRIEND_REQUEST_NOTIFY", generator -> {
            generator.writeStringField("fromUsername", fromUsername);
            generator.writeStringField("fromDisplayName", fromDisplayName);
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
            throw new IllegalStateException("V1 friend request encoding failed", exception);
        }
        return output.toByteArray();
    }

    private static String errorFor(LegacyV1FriendRequestCreationResult.Rejected result) {
        return switch (result) {
            case USER_NOT_FOUND -> "\u7528\u6237\u4e0d\u5b58\u5728";
            case SELF_REQUEST -> "\u4e0d\u80fd\u6dfb\u52a0\u81ea\u5df1\u4e3a\u597d\u53cb";
            case ALREADY_FRIENDS -> "\u5df2\u7ecf\u662f\u597d\u53cb\u4e86";
            case REVERSE_PENDING -> "\u5bf9\u65b9\u5df2\u5411\u4f60\u53d1\u9001\u4e86\u597d\u53cb\u7533\u8bf7\uff0c\u8bf7\u5728\u597d\u53cb\u7533\u8bf7\u4e2d\u5904\u7406";
            case INVALID_TARGET -> "\u7528\u6237\u4e0d\u5b58\u5728";
        };
    }
    private static DecodedRequest malformedIfCreate(String type) {
        return "FRIEND_REQUEST_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_CREATE, null);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, null);
    }
    @FunctionalInterface
    private interface JsonFields { void write(JsonGenerator generator) throws IOException; }
}
