package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded codec for V1 HISTORY_REQ/RSP. */
public final class V1JsonRoomHistoryCodec {
    public static final int MAX_REQUEST_WIRE_BYTES = 4 * 1024;
    public static final int MAX_RESPONSE_WIRE_BYTES = 1024 * 1024;
    public enum RequestKind { HISTORY, MALFORMED_HISTORY, OTHER }
    public record DecodedRequest(RequestKind kind, long roomId, int limit,
            long beforeEpochMillis, Long afterSequence) { }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                    .maxStringLength(1024).maxNumberLength(32).build())
            .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(8).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;
    public V1JsonRoomHistoryCodec(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > MAX_REQUEST_WIRE_BYTES) return other();
        String type = null; Fields data = null;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfHistory(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other(); type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) data = readData(parser);
                else parser.skipChildren();
            }
            if (parser.nextToken() != null) return malformedIfHistory(type);
        } catch (IOException | RuntimeException exception) { return malformedIfHistory(type); }
        if (!"HISTORY_REQ".equals(type)) return other();
        if (data == null) return malformed();
        int limit = data.count() == null || data.count() <= 0 ? 50 : Math.min(data.count(), 100);
        return new DecodedRequest(RequestKind.HISTORY, data.roomId(), limit,
                data.before() == null ? 0 : data.before(), data.afterSequence());
    }

    private static Fields readData(JsonParser parser) throws IOException {
        Long room = null, before = null, after = null; Integer count = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            try {
                switch (field) {
                    case "roomId" -> { if (value != JsonToken.VALUE_NUMBER_INT) invalid = true;
                        else room = parser.getLongValue(); }
                    case "count" -> { if (value != JsonToken.VALUE_NUMBER_INT) invalid = true;
                        else count = parser.getIntValue(); }
                    case "before" -> { if (value != JsonToken.VALUE_NUMBER_INT) invalid = true;
                        else before = parser.getLongValue(); }
                    case "afterSequence" -> { if (value != JsonToken.VALUE_NUMBER_INT) invalid = true;
                        else after = parser.getLongValue(); }
                    default -> invalid = true;
                }
            } catch (RuntimeException exception) { invalid = true; }
            if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) parser.skipChildren();
        }
        return invalid || room == null ? null : new Fields(room, count, before, after);
    }

    public byte[] encode(LegacyV1RoomHistoryResult result, long roomId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", "HISTORY_RSP");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data"); generator.writeNumberField("roomId", roomId);
            generator.writeBooleanField("success", result instanceof LegacyV1RoomHistoryResult.Page);
            if (result instanceof LegacyV1RoomHistoryResult.Page page) {
                generator.writeArrayFieldStart("messages");
                for (LegacyV1RoomHistoryMessage message : page.messages()) writeMessage(generator, message);
                generator.writeEndArray();
                if (page.sequenceMode()) {
                    generator.writeArrayFieldStart("events");
                    for (LegacyV1RoomHistoryDeletion event : page.events()) writeEvent(generator, page.legacyRoomId(), event);
                    generator.writeEndArray(); generator.writeStringField("mode", "sequence");
                    generator.writeNumberField("nextSequence", page.nextSequence());
                    generator.writeNumberField("lastSequence", page.lastSequence());
                    generator.writeBooleanField("hasMore", page.hasMore());
                }
            } else {
                var rejected = (LegacyV1RoomHistoryResult.Rejected) result;
                generator.writeStringField("errorCode", rejected.name());
                generator.writeStringField("error", switch (rejected) {
                    case ROOM_ACCESS_DENIED -> "无权读取该聊天室历史";
                    case INVALID_SEQUENCE_CURSOR -> "消息序列游标无效";
                    case INVALID_REQUEST -> "历史请求无效";
                });
            }
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
        byte[] encoded = output.toByteArray();
        if (encoded.length > MAX_RESPONSE_WIRE_BYTES) throw new IllegalStateException("V1 room history response exceeded wire bound");
        return encoded;
    }

    private static void writeMessage(JsonGenerator g, LegacyV1RoomHistoryMessage m) throws IOException {
        g.writeStartObject(); g.writeNumberField("id", m.legacyMessageId());
        g.writeStringField("content", m.content()); g.writeStringField("contentType", m.contentType());
        g.writeStringField("fileName", m.fileName()); g.writeNumberField("fileSize", m.fileSize());
        g.writeNumberField("fileId", m.legacyFileId());
        if (m.fileCleared()) {
            g.writeBooleanField("fileCleared", true);
            g.writeStringField("clearReason", m.clearReason());
        }
        g.writeBooleanField("recalled", m.recalled()); g.writeNumberField("timestamp", m.acceptedAt().toEpochMilli());
        g.writeStringField("sender", m.senderUsername()); g.writeStringField("senderName", m.senderDisplayName());
        g.writeNumberField("sequence", m.sequence()); g.writeStringField("clientMessageId", m.clientMessageId());
        if (m.mutationSequence() != null) g.writeNumberField("mutationSequence", m.mutationSequence());
        g.writeNumberField("syncSequence", m.syncSequence()); g.writeEndObject();
    }

    private static void writeEvent(JsonGenerator g, long roomId,
            LegacyV1RoomHistoryDeletion e) throws IOException {
        g.writeStartObject(); g.writeStringField("eventType", "messagesDeleted");
        g.writeNumberField("eventId", e.legacyEventId()); g.writeNumberField("roomId", roomId);
        g.writeStringField("operator", e.operatorName()); g.writeStringField("clientOperationId", e.clientOperationId());
        g.writeStringField("mode", e.mode()); writeLongs(g, "messageIds", e.legacyMessageIds());
        writeLongs(g, "deletedFileIds", e.deletedFileIds()); g.writeNumberField("cutoff", e.cutoffEpochMillis());
        g.writeNumberField("cutoffMs", e.cutoffEpochMillis()); g.writeNumberField("timestamp", e.cutoffEpochMillis());
        g.writeNumberField("deletedCount", e.deletedCount()); g.writeNumberField("sequence", e.sequence());
        g.writeNumberField("syncSequence", e.sequence()); g.writeNumberField("eventTimestamp", e.occurredAt().toEpochMilli());
        g.writeEndObject();
    }
    private static void writeLongs(JsonGenerator g, String field, java.util.List<Long> values) throws IOException {
        g.writeArrayFieldStart(field); for (long value : values) g.writeNumber(value); g.writeEndArray();
    }
    private static DecodedRequest malformedIfHistory(String type) { return "HISTORY_REQ".equals(type) ? malformed() : other(); }
    private static DecodedRequest malformed() { return new DecodedRequest(RequestKind.MALFORMED_HISTORY, 0, 0, 0, null); }
    private static DecodedRequest other() { return new DecodedRequest(RequestKind.OTHER, 0, 0, 0, null); }
    private record Fields(long roomId, Integer count, Long before, Long afterSequence) { }
}
