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
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomDirectoryService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomSummary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded codec for the existing V1 room-list envelope. */
public final class V1JsonRoomDirectoryCodec {
    public static final int MAX_REQUEST_WIRE_BYTES = 4 * 1024;
    public static final int MAX_RESPONSE_WIRE_BYTES = 1024 * 1024;

    public enum RequestKind {
        ROOM_LIST,
        MALFORMED_ROOM_LIST,
        OTHER
    }

    private final JsonFactory json;
    private final Clock clock;

    public V1JsonRoomDirectoryCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(8)
                        .maxStringLength(1024)
                        .maxNumberLength(32)
                        .build())
                .streamWriteConstraints(StreamWriteConstraints.builder()
                        .maxNestingDepth(6)
                        .build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
                .build();
    }

    public RequestKind classify(byte[] wire) {
        Objects.requireNonNull(wire, "wire");
        if (wire.length == 0 || wire.length > MAX_REQUEST_WIRE_BYTES) return RequestKind.OTHER;
        String type = null;
        boolean dataObject = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return RequestKind.OTHER;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    return "ROOM_LIST_REQ".equals(type)
                            ? RequestKind.MALFORMED_ROOM_LIST : RequestKind.OTHER;
                }
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return RequestKind.OTHER;
                    type = parser.getText();
                } else if ("data".equals(field)) {
                    dataObject = value == JsonToken.START_OBJECT;
                    parser.skipChildren();
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                return "ROOM_LIST_REQ".equals(type)
                        ? RequestKind.MALFORMED_ROOM_LIST : RequestKind.OTHER;
            }
        } catch (IOException | RuntimeException exception) {
            return "ROOM_LIST_REQ".equals(type)
                    ? RequestKind.MALFORMED_ROOM_LIST : RequestKind.OTHER;
        }
        if (!"ROOM_LIST_REQ".equals(type)) return RequestKind.OTHER;
        return dataObject ? RequestKind.ROOM_LIST : RequestKind.MALFORMED_ROOM_LIST;
    }

    public byte[] encode(List<LegacyV1RoomSummary> rooms) {
        Objects.requireNonNull(rooms, "rooms");
        if (rooms.size() > LegacyV1RoomDirectoryService.MAX_DIRECTORY_ROWS) {
            throw new IllegalArgumentException("room response exceeds its fixed row bound");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeStringField("type", "ROOM_LIST_RSP");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            generator.writeArrayFieldStart("rooms");
            for (LegacyV1RoomSummary room : rooms) {
                generator.writeStartObject();
                generator.writeNumberField("roomId", room.roomId());
                generator.writeStringField("roomName", room.roomName());
                generator.writeNumberField("creatorId", 0);
                generator.writeNumberField("unread", Math.min(room.unread(), Integer.MAX_VALUE));
                generator.writeBooleanField("isAdmin", room.administrator());
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeEndObject();
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 room directory encoding failed", exception);
        }
        byte[] encoded = output.toByteArray();
        if (encoded.length > MAX_RESPONSE_WIRE_BYTES) {
            throw new IllegalStateException("V1 room response exceeded its fixed wire bound");
        }
        return encoded;
    }
}
