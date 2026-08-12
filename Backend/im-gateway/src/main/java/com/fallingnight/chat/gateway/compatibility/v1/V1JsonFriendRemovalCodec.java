package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRemovalResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded codec for V1 FRIEND_REMOVE_REQ/RSP/NOTIFY. */
public final class V1JsonFriendRemovalCodec {
    public enum RequestKind { REMOVE, MALFORMED_REMOVE, OTHER }
    public record DecodedRequest(RequestKind kind, String username) { }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private final Clock clock;

    public V1JsonFriendRemovalCodec(Clock clock) {
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
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfRemove(type);
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
            if (parser.nextToken() != null) return malformedIfRemove(type);
        } catch (IOException | RuntimeException exception) {
            return malformedIfRemove(type);
        }
        if (!"FRIEND_REMOVE_REQ".equals(type)) return other();
        return validData ? new DecodedRequest(RequestKind.REMOVE, username) : malformed();
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

    public byte[] encodeResponse(LegacyV1FriendRemovalResult result) {
        Objects.requireNonNull(result, "result");
        return encode("FRIEND_REMOVE_RSP", generator -> {
            if (result instanceof LegacyV1FriendRemovalResult.Removed removed) {
                generator.writeBooleanField("success", true);
                generator.writeStringField("username", removed.targetUsername());
            } else {
                generator.writeBooleanField("success", false);
                generator.writeStringField("error", errorFor(
                        (LegacyV1FriendRemovalResult.Rejected) result));
            }
        });
    }

    public byte[] encodeNotification(String username, String displayName) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(displayName, "displayName");
        return encode("FRIEND_REMOVE_NOTIFY", generator -> {
            generator.writeStringField("username", username);
            generator.writeStringField("displayName", displayName);
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
            throw new IllegalStateException("V1 friend removal encoding failed", exception);
        }
        return output.toByteArray();
    }

    private static String errorFor(LegacyV1FriendRemovalResult.Rejected result) {
        return result == LegacyV1FriendRemovalResult.Rejected.SELF_REMOVAL
                ? "\u4e0d\u80fd\u5220\u9664\u81ea\u5df1"
                : "\u5220\u9664\u597d\u53cb\u5931\u8d25";
    }
    private static DecodedRequest malformedIfRemove(String type) {
        return "FRIEND_REMOVE_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_REMOVE, null);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, null);
    }
    @FunctionalInterface
    private interface JsonFields { void write(JsonGenerator generator) throws IOException; }
}
