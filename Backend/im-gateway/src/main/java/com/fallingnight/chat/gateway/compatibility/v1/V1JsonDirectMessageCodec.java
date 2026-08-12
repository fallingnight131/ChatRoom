package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectMessageResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded codec for V1 direct text/emoji submission. */
public final class V1JsonDirectMessageCodec {
    public enum RequestKind { SUBMIT, MALFORMED_SUBMIT, OTHER }
    public record DecodedRequest(RequestKind kind, String targetUsername,
            String clientMessageId, String content, String contentType) { }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(65_536).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;
    public V1JsonDirectMessageCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > 70_000) return other();
        String type = null;
        String envelopeId = null;
        Fields data = null;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfSubmit(type);
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("id".equals(field) && value == JsonToken.VALUE_STRING) {
                    envelopeId = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    data = readData(parser);
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return malformedIfSubmit(type);
        } catch (IOException | RuntimeException exception) {
            return malformedIfSubmit(type);
        }
        if (!"FRIEND_CHAT_MSG".equals(type)) return other();
        if (data == null) return malformed();
        String clientId = data.clientMessageId() == null
                || data.clientMessageId().isEmpty() ? envelopeId : data.clientMessageId();
        return new DecodedRequest(RequestKind.SUBMIT, data.targetUsername(), clientId,
                data.content(), data.contentType() == null ? "text" : data.contentType());
    }

    private static Fields readData(JsonParser parser) throws IOException {
        String target = null, clientId = null, content = null, contentType = null;
        boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if (value != JsonToken.VALUE_STRING) { invalid = true; parser.skipChildren(); continue; }
            switch (field) {
                case "friendUsername" -> target = parser.getText();
                case "clientMessageId" -> clientId = parser.getText();
                case "content" -> content = parser.getText();
                case "contentType" -> contentType = parser.getText();
                default -> invalid = true;
            }
        }
        return invalid ? null : new Fields(target, clientId, content, contentType);
    }

    public byte[] encodeResponse(
            LegacyV1DirectMessageResult result, String targetUsername, String clientMessageId) {
        return encode("FRIEND_CHAT_SEND_RSP", generator -> {
            generator.writeBooleanField("success",
                    result instanceof LegacyV1DirectMessageResult.Accepted);
            generator.writeStringField("friendUsername", targetUsername);
            if (clientMessageId != null && !clientMessageId.isEmpty()) {
                generator.writeStringField("clientMessageId", clientMessageId);
            }
            if (result instanceof LegacyV1DirectMessageResult.Accepted accepted) {
                generator.writeNumberField("friendshipId", accepted.legacyFriendshipId());
                generator.writeNumberField("id", accepted.legacyMessageId());
                generator.writeNumberField("sequence", accepted.sequence());
                generator.writeNumberField("timestamp", accepted.acceptedAt().toEpochMilli());
                generator.writeBooleanField("duplicate", accepted.duplicate());
            } else {
                var rejected = (LegacyV1DirectMessageResult.Rejected) result;
                generator.writeStringField("errorCode", rejected.name());
                generator.writeStringField("error", error(rejected));
            }
        });
    }

    public byte[] encodeNotification(LegacyV1DirectMessageResult.Accepted accepted,
            String sender, String senderName, String clientMessageId,
            String content, String contentType) {
        return encode("FRIEND_CHAT_MSG", generator -> {
            generator.writeNumberField("id", accepted.legacyMessageId());
            generator.writeNumberField("friendshipId", accepted.legacyFriendshipId());
            generator.writeNumberField("sequence", accepted.sequence());
            generator.writeStringField("clientMessageId", clientMessageId);
            generator.writeStringField("sender", sender);
            generator.writeStringField("senderName", senderName);
            generator.writeStringField("friendUsername", accepted.targetUsername());
            generator.writeStringField("content", content);
            generator.writeStringField("contentType", contentType);
            generator.writeNumberField("timestamp", accepted.acceptedAt().toEpochMilli());
        });
    }

    private byte[] encode(String type, JsonFields fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", type);
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data"); fields.write(generator);
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 direct message encoding failed", exception);
        }
        return output.toByteArray();
    }
    private static String error(LegacyV1DirectMessageResult.Rejected value) {
        return switch (value) {
            case FRIENDSHIP_ACCESS_DENIED -> "\u65e0\u6743\u5411\u8be5\u7528\u6237\u53d1\u9001\u6d88\u606f";
            case INVALID_MESSAGE -> "\u6d88\u606f\u683c\u5f0f\u65e0\u6548";
            case INVALID_CLIENT_MESSAGE_ID -> "clientMessageId \u5fc5\u987b\u4e3a 1 \u5230 128 \u5b57\u8282";
            case CLIENT_MESSAGE_ID_CONFLICT -> "clientMessageId \u5df2\u7528\u4e8e\u4e0d\u540c\u6d88\u606f";
        };
    }
    private static DecodedRequest malformedIfSubmit(String type) {
        return "FRIEND_CHAT_MSG".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_SUBMIT, null, null, null, null);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, null, null, null, null);
    }
    private record Fields(String targetUsername, String clientMessageId,
            String content, String contentType) { }
    @FunctionalInterface private interface JsonFields {
        void write(JsonGenerator generator) throws IOException;
    }
}
