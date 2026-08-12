package com.fallingnight.chat.gateway.compatibility.v1;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMessageResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
/** Strict bounded codec for V1 room text/emoji submission. */
public final class V1JsonRoomMessageCodec {
    public enum RequestKind { SUBMIT, MALFORMED_SUBMIT, OTHER }
    public record DecodedRequest(RequestKind kind, long roomId, String clientMessageId,
            String content, String contentType) { }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(65_536).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;
    public V1JsonRoomMessageCodec(Clock clock) { this.clock = Objects.requireNonNull(clock); }
    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > 70_000) return other();
        String type = null, envelopeId = null; Data data = null;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfSubmit(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other(); type = parser.getText();
                } else if ("id".equals(field) && value == JsonToken.VALUE_STRING)
                    envelopeId = parser.getText();
                else if ("data".equals(field) && value == JsonToken.START_OBJECT)
                    data = readData(parser);
                else parser.skipChildren();
            }
            if (parser.nextToken() != null) return malformedIfSubmit(type);
        } catch (IOException | RuntimeException exception) { return malformedIfSubmit(type); }
        if (!"CHAT_MSG".equals(type)) return other();
        if (data == null) return malformed();
        String clientId = data.clientMessageId() == null || data.clientMessageId().isEmpty()
                ? envelopeId : data.clientMessageId();
        return new DecodedRequest(RequestKind.SUBMIT, data.roomId(), clientId,
                data.content(), data.contentType() == null ? "text" : data.contentType());
    }
    private static Data readData(JsonParser parser) throws IOException {
        Long roomId = null; String client = null, content = null, type = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            switch (field) {
                case "roomId" -> {
                    if (value != JsonToken.VALUE_NUMBER_INT) invalid = true;
                    else try { roomId = parser.getLongValue(); } catch (RuntimeException e) { invalid = true; }
                }
                case "clientMessageId" -> { if (value != JsonToken.VALUE_STRING) invalid = true; else client = parser.getText(); }
                case "content" -> { if (value != JsonToken.VALUE_STRING) invalid = true; else content = parser.getText(); }
                case "contentType" -> { if (value != JsonToken.VALUE_STRING) invalid = true; else type = parser.getText(); }
                case "sender" -> { if (value != JsonToken.VALUE_STRING) invalid = true; }
                default -> invalid = true;
            }
            if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) parser.skipChildren();
        }
        return invalid || roomId == null ? null : new Data(roomId, client, content, type);
    }
    public byte[] encodeResponse(LegacyV1RoomMessageResult result, long roomId, String clientId) {
        return encode("CHAT_SEND_RSP", generator -> {
            generator.writeBooleanField("success", result instanceof LegacyV1RoomMessageResult.Accepted);
            generator.writeNumberField("roomId", roomId);
            if (clientId != null && !clientId.isEmpty()) generator.writeStringField("clientMessageId", clientId);
            if (result instanceof LegacyV1RoomMessageResult.Accepted accepted) {
                generator.writeNumberField("id", accepted.legacyMessageId());
                generator.writeNumberField("sequence", accepted.sequence());
                generator.writeNumberField("timestamp", accepted.acceptedAt().toEpochMilli());
                generator.writeBooleanField("duplicate", accepted.duplicate());
            } else {
                var rejected = (LegacyV1RoomMessageResult.Rejected) result;
                generator.writeStringField("errorCode", rejected.name());
                generator.writeStringField("error", switch (rejected) {
                    case ROOM_ACCESS_DENIED -> "无权向该聊天室发送消息";
                    case INVALID_MESSAGE -> "消息格式无效";
                    case INVALID_CLIENT_MESSAGE_ID -> "clientMessageId 必须为 1 到 128 字节";
                    case CLIENT_MESSAGE_ID_CONFLICT -> "clientMessageId 已用于不同消息";
                });
            }
        });
    }
    public byte[] encodeNotification(LegacyV1RoomMessageResult.Accepted accepted,
            String sender, String senderName, String clientId, String content, String type) {
        return encode("CHAT_MSG", generator -> {
            generator.writeNumberField("roomId", accepted.legacyRoomId());
            generator.writeNumberField("id", accepted.legacyMessageId());
            generator.writeNumberField("sequence", accepted.sequence());
            generator.writeStringField("clientMessageId", clientId);
            generator.writeStringField("sender", sender); generator.writeStringField("senderName", senderName);
            generator.writeStringField("content", content); generator.writeStringField("contentType", type);
            generator.writeNumberField("timestamp", accepted.acceptedAt().toEpochMilli());
        });
    }
    private byte[] encode(String type, Fields fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", type);
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis()); generator.writeObjectFieldStart("data");
            fields.write(generator); generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
        return output.toByteArray();
    }
    private static DecodedRequest malformedIfSubmit(String type) { return "CHAT_MSG".equals(type) ? malformed() : other(); }
    private static DecodedRequest malformed() { return new DecodedRequest(RequestKind.MALFORMED_SUBMIT, 0, null, null, null); }
    private static DecodedRequest other() { return new DecodedRequest(RequestKind.OTHER, 0, null, null, null); }
    private record Data(long roomId, String clientMessageId, String content, String contentType) { }
    @FunctionalInterface private interface Fields { void write(JsonGenerator generator) throws IOException; }
}
