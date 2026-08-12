package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectRecallResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict V1 FRIEND_RECALL_REQ/RSP/NOTIFY codec. */
public final class V1JsonDirectRecallCodec {
    public enum RequestKind { RECALL, MALFORMED_RECALL, OTHER }
    public record DecodedRequest(RequestKind kind, long legacyMessageId) { }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;
    public V1JsonDirectRecallCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > 4096) return other();
        String type = null;
        Long messageId = null;
        boolean validData = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfRecall(type);
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    Data data = readData(parser);
                    if (data == null) return malformedIfRecall(type);
                    messageId = data.messageId(); validData = true;
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return malformedIfRecall(type);
        } catch (IOException | RuntimeException exception) {
            return malformedIfRecall(type);
        }
        if (!"FRIEND_RECALL_REQ".equals(type)) return other();
        return validData && messageId != null
                ? new DecodedRequest(RequestKind.RECALL, messageId) : malformed();
    }

    private static Data readData(JsonParser parser) throws IOException {
        Long id = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            if ("messageId".equals(field)) {
                if (value != JsonToken.VALUE_NUMBER_INT) invalid = true;
                else try { id = parser.getLongValue(); } catch (RuntimeException e) { invalid = true; }
            } else if ("friendUsername".equals(field)) {
                if (value != JsonToken.VALUE_STRING) invalid = true;
            } else invalid = true;
            if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) parser.skipChildren();
        }
        return invalid ? null : new Data(id);
    }

    public byte[] encodeResponse(LegacyV1DirectRecallResult result, long requestMessageId) {
        return encode("FRIEND_RECALL_RSP", generator -> {
            generator.writeNumberField("messageId", requestMessageId);
            generator.writeBooleanField("success",
                    result instanceof LegacyV1DirectRecallResult.Recalled);
            if (result instanceof LegacyV1DirectRecallResult.Recalled recalled) {
                generator.writeStringField("friendUsername", recalled.targetUsername());
                generator.writeNumberField("friendshipId", recalled.legacyFriendshipId());
                generator.writeBooleanField("duplicate", recalled.duplicate());
                generator.writeNumberField("mutationSequence", recalled.mutationSequence());
            } else {
                var rejected = (LegacyV1DirectRecallResult.Rejected) result;
                generator.writeStringField("errorCode", rejected.name());
                generator.writeStringField("error", "无法撤回（超时或非本人消息）");
            }
        });
    }

    public byte[] encodeNotification(LegacyV1DirectRecallResult.Recalled recalled,
            String actorUsername) {
        return encode("FRIEND_RECALL_NOTIFY", generator -> {
            generator.writeNumberField("messageId", recalled.legacyMessageId());
            generator.writeStringField("friendUsername", actorUsername);
            generator.writeNumberField("friendshipId", recalled.legacyFriendshipId());
            generator.writeNumberField("mutationSequence", recalled.mutationSequence());
        });
    }

    private byte[] encode(String type, Fields fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", type);
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data"); fields.write(generator);
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 direct recall encoding failed", exception);
        }
        return output.toByteArray();
    }
    private static DecodedRequest malformedIfRecall(String type) {
        return "FRIEND_RECALL_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_RECALL, 0);
    }
    private static DecodedRequest other() { return new DecodedRequest(RequestKind.OTHER, 0); }
    private record Data(Long messageId) { }
    @FunctionalInterface private interface Fields { void write(JsonGenerator generator) throws IOException; }
}
