package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectReadResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict V1 MARK_FRIEND_READ classifier and FRIEND_READ_NOTIFY encoder. */
public final class V1JsonDirectReadCodec {
    public enum RequestKind { MARK_READ, MALFORMED_MARK_READ, OTHER }
    public record DecodedRequest(RequestKind kind, long legacyFriendshipId) { }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;
    public V1JsonDirectReadCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > 4096) return other();
        String type = null; Long friendshipId = null; boolean validData = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfMark(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    friendshipId = readData(parser);
                    if (friendshipId == null) return malformedIfMark(type);
                    validData = true;
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return malformedIfMark(type);
        } catch (IOException | RuntimeException exception) {
            return malformedIfMark(type);
        }
        if (!"MARK_FRIEND_READ".equals(type)) return other();
        return validData ? new DecodedRequest(RequestKind.MARK_READ, friendshipId) : malformed();
    }

    private static Long readData(JsonParser parser) throws IOException {
        Long friendshipId = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            if ("friendshipId".equals(field) && value == JsonToken.VALUE_NUMBER_INT) {
                try { friendshipId = parser.getLongValue(); }
                catch (RuntimeException exception) { invalid = true; }
            } else invalid = true;
            if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                parser.skipChildren();
            }
        }
        return invalid ? null : friendshipId;
    }

    public byte[] encodeNotification(
            LegacyV1DirectReadResult.Marked marked, String readerUsername) {
        Objects.requireNonNull(marked, "marked");
        Objects.requireNonNull(readerUsername, "readerUsername");
        ByteArrayOutputStream output = new ByteArrayOutputStream(384);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeStringField("type", "FRIEND_READ_NOTIFY");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            generator.writeNumberField("friendshipId", marked.legacyFriendshipId());
            generator.writeStringField("readerUsername", readerUsername);
            generator.writeNumberField("lastReadMessageId", marked.legacyLastReadMessageId());
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 direct read encoding failed", exception);
        }
        return output.toByteArray();
    }

    private static DecodedRequest malformedIfMark(String type) {
        return "MARK_FRIEND_READ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_MARK_READ, 0);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, 0);
    }
}
