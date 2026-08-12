package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded V1 ROOM_SEARCH_REQ/RSP codec. */
public final class V1JsonRoomSearchCodec {
    public enum RequestKind { SEARCH, MALFORMED_SEARCH, OTHER }
    public record DecodedRequest(RequestKind kind, String keyword) { }
    private static final String EMPTY_ERROR = "\u641c\u7d22\u5173\u952e\u8bcd\u4e0d\u80fd\u4e3a\u7a7a";
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;
    public V1JsonRoomSearchCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        Objects.requireNonNull(wire, "wire");
        if (wire.length == 0 || wire.length > 4096) return other();
        String type = null, keyword = null; boolean validData = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfSearch(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    keyword = readData(parser); validData = keyword != null;
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return malformedIfSearch(type);
        } catch (IOException | RuntimeException exception) { return malformedIfSearch(type); }
        if (!"ROOM_SEARCH_REQ".equals(type)) return other();
        return validData ? new DecodedRequest(RequestKind.SEARCH, keyword) : malformed();
    }

    private static String readData(JsonParser parser) throws IOException {
        String keyword = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            if ("keyword".equals(field) && value == JsonToken.VALUE_STRING) {
                keyword = parser.getText();
            } else { invalid = true; parser.skipChildren(); }
        }
        return invalid ? null : keyword;
    }

    public byte[] encode(LegacyV1RoomSearchResult result) {
        Objects.requireNonNull(result, "result");
        ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", "ROOM_SEARCH_RSP");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            if (result instanceof LegacyV1RoomSearchResult.Found found) {
                generator.writeBooleanField("success", true);
                generator.writeArrayFieldStart("rooms");
                for (LegacyV1RoomSearchRoom room : found.rooms()) {
                    generator.writeStartObject(); generator.writeNumberField("roomId", room.roomId());
                    generator.writeStringField("roomName", room.roomName());
                    generator.writeNumberField("creatorId", room.creatorId());
                    generator.writeNumberField("memberCount", room.memberCount());
                    generator.writeEndObject();
                }
                generator.writeEndArray();
            } else {
                generator.writeBooleanField("success", false);
                generator.writeStringField("error", EMPTY_ERROR);
            }
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 room search encoding failed", exception);
        }
        byte[] encoded = output.toByteArray();
        if (encoded.length > 64 * 1024) {
            throw new IllegalStateException("V1 room search response exceeded wire bound");
        }
        return encoded;
    }
    private static DecodedRequest malformedIfSearch(String type) {
        return "ROOM_SEARCH_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_SEARCH, null);
    }
    private static DecodedRequest other() { return new DecodedRequest(RequestKind.OTHER, null); }
}
