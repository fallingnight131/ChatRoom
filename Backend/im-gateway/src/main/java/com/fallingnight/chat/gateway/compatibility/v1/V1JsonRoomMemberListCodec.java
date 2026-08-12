package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.*;
import com.fallingnight.chat.application.compatibility.v1.*;
import java.io.*;
import java.time.Clock;
import java.util.*;

/** Strict bounded codec for V1 USER_LIST_REQ/RSP. */
public final class V1JsonRoomMemberListCodec {
    public static final int MAX_REQUEST_BYTES = 4096;
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    public enum RequestKind { LIST, MALFORMED_LIST, OTHER }
    public record DecodedRequest(RequestKind kind, long roomId) { }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(6).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .disable(JsonWriteFeature.ESCAPE_NON_ASCII).build();
    private final Clock clock;
    public V1JsonRoomMemberListCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > MAX_REQUEST_BYTES) return other();
        String type = null; Long roomId = null; boolean validData = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfOwned(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    roomId = readData(parser); validData = roomId != null;
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return malformedIfOwned(type);
        } catch (IOException | RuntimeException exception) { return malformedIfOwned(type); }
        if (!"USER_LIST_REQ".equals(type)) return other();
        return validData ? new DecodedRequest(RequestKind.LIST, roomId) : malformed();
    }

    private static Long readData(JsonParser parser) throws IOException {
        Long roomId = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            if ("roomId".equals(field) && value == JsonToken.VALUE_NUMBER_INT) {
                roomId = parser.getLongValue();
            } else { invalid = true; parser.skipChildren(); }
        }
        return invalid ? null : roomId;
    }

    public byte[] encode(LegacyV1RoomMemberListResult result, long requestedRoomId) {
        Objects.requireNonNull(result, "result");
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", "USER_LIST_RSP");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            if (result instanceof LegacyV1RoomMemberListResult.Listed listed) {
                if (listed.users().size() > LegacyV1RoomMemberListService.MAX_MEMBERS) {
                    throw new IllegalArgumentException("V1 member response exceeds row bound");
                }
                generator.writeBooleanField("success", true);
                generator.writeNumberField("roomId", listed.legacyRoomId());
                generator.writeArrayFieldStart("users");
                for (var user : listed.users()) {
                    generator.writeStartObject();
                    generator.writeStringField("username", user.username());
                    generator.writeStringField("displayName", user.displayName());
                    generator.writeBooleanField("isAdmin", user.admin());
                    generator.writeBooleanField("isOnline", user.online());
                    generator.writeEndObject();
                }
                generator.writeEndArray();
            } else {
                var rejected = (LegacyV1RoomMemberListResult.Rejected) result;
                generator.writeBooleanField("success", false);
                if (requestedRoomId > 0 && requestedRoomId <= Integer.MAX_VALUE) {
                    generator.writeNumberField("roomId", requestedRoomId);
                }
                generator.writeStringField("errorCode", rejected.name());
                generator.writeStringField("error", rejected ==
                        LegacyV1RoomMemberListResult.Rejected.ROOM_TOO_LARGE
                        ? "聊天室成员过多，请使用新版客户端"
                        : "无权访问该聊天室");
            }
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 room member encoding failed", exception);
        }
        byte[] encoded = output.toByteArray();
        if (encoded.length > MAX_RESPONSE_BYTES) {
            throw new IllegalStateException("V1 room member response exceeded wire bound");
        }
        return encoded;
    }
    private static DecodedRequest malformedIfOwned(String type) {
        return "USER_LIST_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_LIST, 0);
    }
    private static DecodedRequest other() { return new DecodedRequest(RequestKind.OTHER, 0); }
}
