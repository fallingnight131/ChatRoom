package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded V1 DELETE_MSGS request, response, and live-effect codec. */
public final class V1JsonRoomMessageDeletionCodec {
    public static final int MAX_REQUEST_BYTES = 8192;
    public enum RequestKind { DELETE, MALFORMED, OTHER }
    public record DecodedRequest(RequestKind kind, long roomId, String clientOperationId,
            String mode, List<Long> messageIds, long cutoffEpochMillis) { }
    public record Notifications(byte[] messagesDeleted, byte[] systemMessage) { }

    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;

    public V1JsonRoomMessageDeletionCodec(Clock clock) {
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
        if (!"DELETE_MSGS_REQ".equals(type)) return other();
        if (data == null) return malformed();
        String operation = data.clientOperationId() == null
                || data.clientOperationId().isEmpty() ? envelopeId : data.clientOperationId();
        return new DecodedRequest(RequestKind.DELETE, data.roomId(), operation, data.mode(),
                data.messageIds(), data.cutoffEpochMillis());
    }

    private static Data readData(JsonParser parser) throws IOException {
        Long roomId = null, cutoff = null; String operation = null, mode = null;
        List<Long> ids = List.of(); boolean idsSeen = false, invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            if ("roomId".equals(field) && value == JsonToken.VALUE_NUMBER_INT) {
                roomId = parser.getLongValue();
            } else if ("clientOperationId".equals(field) && value == JsonToken.VALUE_STRING) {
                operation = parser.getText();
            } else if ("mode".equals(field) && value == JsonToken.VALUE_STRING) {
                mode = parser.getText();
            } else if ("timestamp".equals(field) && value == JsonToken.VALUE_NUMBER_INT) {
                cutoff = parser.getLongValue();
            } else if ("messageIds".equals(field) && value == JsonToken.START_ARRAY) {
                idsSeen = true; ArrayList<Long> values = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                        invalid = true; parser.skipChildren();
                    } else values.add(parser.getLongValue());
                    if (values.size() > LegacyV1RoomMessageDeletionService.MAX_SELECTED)
                        invalid = true;
                }
                ids = List.copyOf(values);
            } else { invalid = true; parser.skipChildren(); }
        }
        if (invalid || roomId == null || mode == null) return null;
        boolean selected = "selected".equals(mode);
        if (selected != idsSeen) return null;
        return new Data(roomId, operation, mode, ids, cutoff == null ? 0 : cutoff);
    }

    public byte[] encodeResponse(LegacyV1RoomMessageDeletionResult result,
            long roomId, String mode, String operationId) {
        return encode("DELETE_MSGS_RSP", generator -> {
            generator.writeNumberField("roomId", roomId);
            generator.writeStringField("mode", mode == null ? "" : mode);
            generator.writeStringField("clientOperationId", operationId == null ? "" : operationId);
            generator.writeBooleanField("success",
                    result instanceof LegacyV1RoomMessageDeletionResult.Deleted);
            if (result instanceof LegacyV1RoomMessageDeletionResult.Deleted deleted) {
                writeDeleted(generator, deleted);
                generator.writeBooleanField("duplicate", deleted.duplicate());
            } else writeRejection(generator,
                    (LegacyV1RoomMessageDeletionResult.Rejected) result);
        });
    }

    public Notifications encodeNotifications(
            LegacyV1RoomMessageDeletionResult.Deleted deleted, String operator) {
        byte[] deletion = encode("DELETE_MSGS_NOTIFY", generator -> {
            generator.writeNumberField("roomId", deleted.legacyRoomId());
            generator.writeStringField("mode", deleted.mode().wireValue());
            generator.writeStringField("clientOperationId", deleted.clientOperationId());
            generator.writeStringField("eventType", "messagesDeleted");
            generator.writeStringField("operator", operator);
            writeDeleted(generator, deleted);
        });
        byte[] system = encode("SYSTEM_MSG", generator -> {
            generator.writeNumberField("roomId", deleted.legacyRoomId());
            generator.writeStringField("content", systemText(deleted, operator));
        });
        return new Notifications(deletion, system);
    }

    private static void writeDeleted(JsonGenerator generator,
            LegacyV1RoomMessageDeletionResult.Deleted deleted) throws IOException {
        generator.writeNumberField("deletedCount", deleted.deletedCount());
        writeLongs(generator, "messageIds", deleted.legacyMessageIds());
        writeLongs(generator, "deletedFileIds", deleted.legacyFileIds());
        generator.writeNumberField("timestamp", deleted.cutoffEpochMillis());
        generator.writeNumberField("sequence", deleted.sequence());
        generator.writeNumberField("syncSequence", deleted.sequence());
        generator.writeNumberField("eventTimestamp", deleted.occurredAt().toEpochMilli());
    }

    private static void writeRejection(JsonGenerator generator,
            LegacyV1RoomMessageDeletionResult.Rejected rejected) throws IOException {
        generator.writeStringField("errorCode", switch (rejected) {
            case ROOM_ADMIN_REQUIRED -> "ADMIN_DELETE_ACCESS_DENIED";
            case INVALID_INPUT -> "INVALID_DELETE_REQUEST";
            case CLIENT_OPERATION_ID_CONFLICT -> "CLIENT_OPERATION_ID_CONFLICT";
            case DELETE_SCOPE_TOO_LARGE -> "DELETE_SCOPE_TOO_LARGE";
        });
        generator.writeStringField("error", switch (rejected) {
            case ROOM_ADMIN_REQUIRED -> "您没有管理员权限";
            case INVALID_INPUT -> "删除请求无效";
            case CLIENT_OPERATION_ID_CONFLICT -> "clientOperationId 已用于不同删除命令";
            case DELETE_SCOPE_TOO_LARGE -> "删除范围过大，请联系管理员分批处理";
        });
    }

    private static String systemText(LegacyV1RoomMessageDeletionResult.Deleted deleted,
            String operator) {
        return switch (deleted.mode()) {
            case ALL -> "管理员 " + operator + " 清空了所有聊天记录";
            case SELECTED -> "管理员 " + operator + " 删除了 "
                    + deleted.deletedCount() + " 条消息";
            case BEFORE -> "管理员 " + operator + " 删除了 "
                    + deleted.deletedCount() + " 条旧消息";
            case AFTER -> "管理员 " + operator + " 删除了 "
                    + deleted.deletedCount() + " 条近期消息";
        };
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
        if (encoded.length > 65_536) throw new IllegalStateException("V1 deletion frame too large");
        return encoded;
    }

    private static DecodedRequest owned(String type) {
        return "DELETE_MSGS_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED, 0, null, null, List.of(), 0);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, 0, null, null, List.of(), 0);
    }
    private record Data(long roomId, String clientOperationId, String mode,
            List<Long> messageIds, long cutoffEpochMillis) { }
    @FunctionalInterface private interface Fields {
        void write(JsonGenerator generator) throws IOException;
    }
}
