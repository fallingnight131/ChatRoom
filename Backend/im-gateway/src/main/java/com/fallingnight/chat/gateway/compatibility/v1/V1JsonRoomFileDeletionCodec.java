package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFileDeletionResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded V1 ROOM_FILES_DELETE request, response, and notification codec. */
public final class V1JsonRoomFileDeletionCodec {
    public static final int MAX_REQUEST_BYTES = 8192;
    public enum RequestKind { DELETE, MALFORMED, OTHER }
    public record DecodedRequest(RequestKind kind, long roomId,
            String clientOperationId, List<Long> fileIds) { }
    public record Notifications(byte[] messagesDeleted, byte[] roomFiles) { }

    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;
    public V1JsonRoomFileDeletionCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > MAX_REQUEST_BYTES) return other();
        String type = null, envelopeId = null; Data data = null;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return owned(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("id".equals(field) && value == JsonToken.VALUE_STRING) {
                    envelopeId = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    data = readData(parser);
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return owned(type);
        } catch (IOException | RuntimeException exception) { return owned(type); }
        if (!"ROOM_FILES_DELETE_REQ".equals(type)) return other();
        if (data == null) return malformed();
        String operation = data.clientOperationId() == null
                || data.clientOperationId().isEmpty() ? envelopeId : data.clientOperationId();
        return new DecodedRequest(RequestKind.DELETE, data.roomId(), operation, data.fileIds());
    }

    private static Data readData(JsonParser parser) throws IOException {
        Long roomId = null; String operation = null; List<Long> ids = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            if ("roomId".equals(field) && value == JsonToken.VALUE_NUMBER_INT) {
                roomId = parser.getLongValue();
            } else if ("clientOperationId".equals(field) && value == JsonToken.VALUE_STRING) {
                operation = parser.getText();
            } else if ("fileIds".equals(field) && value == JsonToken.START_ARRAY) {
                ids = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                        invalid = true; parser.skipChildren();
                    } else ids.add(parser.getLongValue());
                    if (ids.size() > 100) invalid = true;
                }
            } else { invalid = true; parser.skipChildren(); }
        }
        return invalid || roomId == null || ids == null
                ? null : new Data(roomId, operation, List.copyOf(ids));
    }

    public byte[] encodeResponse(LegacyV1RoomFileDeletionResult result,
            long roomId, String operationId) {
        return encode("ROOM_FILES_DELETE_RSP", generator -> {
            generator.writeNumberField("roomId", roomId);
            generator.writeStringField("clientOperationId", operationId == null ? "" : operationId);
            generator.writeBooleanField("success",
                    result instanceof LegacyV1RoomFileDeletionResult.Deleted);
            if (result instanceof LegacyV1RoomFileDeletionResult.Deleted deleted) {
                writeDeleted(generator, deleted);
                generator.writeBooleanField("duplicate", deleted.duplicate());
            } else {
                var rejected = (LegacyV1RoomFileDeletionResult.Rejected) result;
                generator.writeStringField("errorCode", switch (rejected) {
                    case ROOM_ADMIN_REQUIRED -> "ADMIN_DELETE_ACCESS_DENIED";
                    case INVALID_INPUT -> "INVALID_MESSAGE_SELECTION";
                    case CLIENT_OPERATION_ID_CONFLICT -> "CLIENT_OPERATION_ID_CONFLICT";
                });
                generator.writeStringField("error", switch (rejected) {
                    case ROOM_ADMIN_REQUIRED -> "您没有管理员权限";
                    case INVALID_INPUT -> "必须选择 1 到 100 个有效文件";
                    case CLIENT_OPERATION_ID_CONFLICT -> "clientOperationId 已用于不同删除命令";
                });
            }
        });
    }

    public Notifications encodeNotifications(
            LegacyV1RoomFileDeletionResult.Deleted deleted, String operator) {
        byte[] messages = encode("DELETE_MSGS_NOTIFY", generator -> {
            generator.writeNumberField("roomId", deleted.legacyRoomId());
            generator.writeStringField("clientOperationId", deleted.clientOperationId());
            generator.writeStringField("mode", "selected");
            generator.writeStringField("eventType", "messagesDeleted");
            generator.writeStringField("operator", operator);
            writeDeleted(generator, deleted);
        });
        byte[] files = encode("ROOM_FILES_NOTIFY", generator -> {
            generator.writeNumberField("roomId", deleted.legacyRoomId());
            writeLongs(generator, "deletedFileIds", deleted.legacyFileIds());
            generator.writeNumberField("usedFileSpace", deleted.usedFileSpace());
            generator.writeNumberField("maxFileSpace", deleted.maxFileSpace());
            generator.writeStringField("operator", operator);
        });
        return new Notifications(messages, files);
    }

    private static void writeDeleted(JsonGenerator generator,
            LegacyV1RoomFileDeletionResult.Deleted deleted) throws IOException {
        generator.writeNumberField("deletedCount", deleted.deletedCount());
        writeLongs(generator, "messageIds", deleted.legacyMessageIds());
        writeLongs(generator, "deletedFileIds", deleted.legacyFileIds());
        generator.writeNumberField("sequence", deleted.sequence());
        generator.writeNumberField("syncSequence", deleted.sequence());
        generator.writeNumberField("eventTimestamp", deleted.occurredAt().toEpochMilli());
        generator.writeNumberField("usedFileSpace", deleted.usedFileSpace());
        generator.writeNumberField("maxFileSpace", deleted.maxFileSpace());
    }
    private static void writeLongs(JsonGenerator generator, String field, List<Long> values)
            throws IOException {
        generator.writeArrayFieldStart(field);
        for (long value : values) generator.writeNumber(value);
        generator.writeEndArray();
    }
    private byte[] encode(String type, Fields fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", type);
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data"); fields.write(generator);
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
        byte[] encoded = output.toByteArray();
        if (encoded.length > 65_536) throw new IllegalStateException("V1 deletion response too large");
        return encoded;
    }
    private static DecodedRequest owned(String type) {
        return "ROOM_FILES_DELETE_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED, 0, null, List.of());
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, 0, null, List.of());
    }
    private record Data(long roomId, String clientOperationId, List<Long> fileIds) { }
    @FunctionalInterface private interface Fields {
        void write(JsonGenerator generator) throws IOException;
    }
}
