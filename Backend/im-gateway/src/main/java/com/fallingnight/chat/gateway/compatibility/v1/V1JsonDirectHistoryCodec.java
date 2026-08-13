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
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryMessage;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded codec for V1 FRIEND_HISTORY_REQ/RSP. */
public final class V1JsonDirectHistoryCodec {
    public static final int MAX_REQUEST_WIRE_BYTES = 4 * 1024;
    public static final int MAX_RESPONSE_WIRE_BYTES = 1024 * 1024;
    public enum RequestKind { HISTORY, MALFORMED_HISTORY, OTHER }
    public record DecodedRequest(RequestKind kind, String targetUsername, int limit,
            long beforeEpochMillis, Long afterSequence) { }

    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(8).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .build();
    private final Clock clock;

    public V1JsonDirectHistoryCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > MAX_REQUEST_WIRE_BYTES) {
            return other();
        }
        String type = null;
        Fields data = null;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfHistory(type);
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    data = readData(parser);
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) return malformedIfHistory(type);
        } catch (IOException | RuntimeException exception) {
            return malformedIfHistory(type);
        }
        if (!"FRIEND_HISTORY_REQ".equals(type)) return other();
        if (data == null) return malformed();
        int limit = data.count() == null || data.count() <= 0
                ? 50 : Math.min(data.count(), 100);
        return new DecodedRequest(RequestKind.HISTORY, data.targetUsername(), limit,
                data.before() == null ? 0 : data.before(), data.afterSequence());
    }

    private static Fields readData(JsonParser parser) throws IOException {
        String target = null;
        Integer count = null;
        Long before = null;
        Long after = null;
        boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            try {
                switch (field) {
                    case "friendUsername" -> {
                        if (value != JsonToken.VALUE_STRING) invalid = true;
                        else target = parser.getText();
                    }
                    case "count" -> {
                        if (value != JsonToken.VALUE_NUMBER_INT) invalid = true;
                        else count = parser.getIntValue();
                    }
                    case "before" -> {
                        if (value != JsonToken.VALUE_NUMBER_INT) invalid = true;
                        else before = parser.getLongValue();
                    }
                    case "afterSequence" -> {
                        if (value != JsonToken.VALUE_NUMBER_INT) invalid = true;
                        else after = parser.getLongValue();
                    }
                    default -> invalid = true;
                }
            } catch (RuntimeException exception) {
                invalid = true;
            }
            if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                parser.skipChildren();
            }
        }
        return invalid ? null : new Fields(target, count, before, after);
    }

    public byte[] encode(LegacyV1DirectHistoryResult result, String targetUsername) {
        Objects.requireNonNull(result, "result");
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeStringField("type", "FRIEND_HISTORY_RSP");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            generator.writeStringField("friendUsername",
                    targetUsername == null ? "" : targetUsername);
            generator.writeBooleanField("success",
                    result instanceof LegacyV1DirectHistoryResult.Page);
            if (result instanceof LegacyV1DirectHistoryResult.Page page) {
                generator.writeNumberField("friendshipId", page.legacyFriendshipId());
                generator.writeArrayFieldStart("messages");
                for (LegacyV1DirectHistoryMessage message : page.messages()) {
                    writeMessage(generator, page.legacyFriendshipId(), message);
                }
                generator.writeEndArray();
                if (page.sequenceMode()) {
                    generator.writeStringField("mode", "sequence");
                    generator.writeNumberField("nextSequence", page.nextSequence());
                    generator.writeNumberField("lastSequence", page.lastSequence());
                    generator.writeBooleanField("hasMore", page.hasMore());
                }
            } else {
                var rejected = (LegacyV1DirectHistoryResult.Rejected) result;
                generator.writeStringField("errorCode", rejected.name());
                generator.writeStringField("error", error(rejected));
            }
            generator.writeEndObject();
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 direct history encoding failed", exception);
        }
        byte[] encoded = output.toByteArray();
        if (encoded.length > MAX_RESPONSE_WIRE_BYTES) {
            throw new IllegalStateException("V1 direct history response exceeded wire bound");
        }
        return encoded;
    }

    private static void writeMessage(JsonGenerator generator, long friendshipId,
            LegacyV1DirectHistoryMessage message) throws IOException {
        generator.writeStartObject();
        generator.writeNumberField("id", message.legacyMessageId());
        generator.writeStringField("content", message.content());
        generator.writeStringField("contentType", message.contentType());
        generator.writeStringField("fileName", message.fileName());
        generator.writeNumberField("fileSize", message.fileSize());
        generator.writeNumberField("fileId",
                message.legacyFileId() == 0 ? 0 : -message.legacyFileId());
        if (message.fileCleared()) {
            generator.writeBooleanField("fileCleared", true);
            generator.writeStringField("clearReason", message.clearReason());
        }
        generator.writeBooleanField("recalled", message.recalled());
        generator.writeNumberField("timestamp", message.acceptedAt().toEpochMilli());
        generator.writeStringField("sender", message.senderUsername());
        generator.writeStringField("senderName", message.senderDisplayName());
        generator.writeNumberField("friendshipId", friendshipId);
        generator.writeNumberField("sequence", message.sequence());
        generator.writeStringField("clientMessageId", message.clientMessageId());
        if (message.mutationSequence() != null) {
            generator.writeNumberField("mutationSequence", message.mutationSequence());
        }
        generator.writeNumberField("syncSequence", message.syncSequence());
        generator.writeEndObject();
    }

    private static String error(LegacyV1DirectHistoryResult.Rejected rejected) {
        return switch (rejected) {
            case FRIENDSHIP_ACCESS_DENIED -> "无权读取该会话历史";
            case INVALID_SEQUENCE_CURSOR -> "消息序列游标无效";
            case INVALID_REQUEST -> "历史请求无效";
        };
    }

    private static DecodedRequest malformedIfHistory(String type) {
        return "FRIEND_HISTORY_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_HISTORY, null, 0, 0, null);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, null, 0, 0, null);
    }
    private record Fields(String targetUsername, Integer count, Long before, Long afterSequence) { }
}
