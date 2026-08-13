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
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFile;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFilesResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded codec for the administrator-only V1 ROOM_FILES_REQ/RSP shape. */
public final class V1JsonRoomFilesCodec {
    public static final int MAX_REQUEST_BYTES = 4096;
    public static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final DateTimeFormatter V1_UTC =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    public enum RequestKind { READ, MALFORMED, OTHER }
    public record DecodedRequest(RequestKind kind, long roomId) { }

    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(8).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .disable(JsonWriteFeature.ESCAPE_NON_ASCII).build();
    private final Clock clock;

    public V1JsonRoomFilesCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > MAX_REQUEST_BYTES) return other();
        String type = null;
        Long roomId = null;
        boolean validData = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfOwned(type);
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    roomId = readData(parser);
                    validData = roomId != null;
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) return malformedIfOwned(type);
        } catch (IOException | RuntimeException exception) {
            return malformedIfOwned(type);
        }
        if (!"ROOM_FILES_REQ".equals(type)) return other();
        return validData ? new DecodedRequest(RequestKind.READ, roomId) : malformed();
    }

    private static Long readData(JsonParser parser) throws IOException {
        Long roomId = null;
        boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if ("roomId".equals(field) && value == JsonToken.VALUE_NUMBER_INT) {
                roomId = parser.getLongValue();
            } else {
                invalid = true;
                parser.skipChildren();
            }
        }
        return invalid ? null : roomId;
    }

    public byte[] encode(LegacyV1RoomFilesResult result, long requestedRoomId) {
        Objects.requireNonNull(result, "result");
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeStringField("type", "ROOM_FILES_RSP");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            if (result instanceof LegacyV1RoomFilesResult.Read read) {
                generator.writeBooleanField("success", true);
                generator.writeNumberField("roomId", read.legacyRoomId());
                generator.writeArrayFieldStart("files");
                for (LegacyV1RoomFile file : read.files().files()) {
                    generator.writeStartObject();
                    generator.writeNumberField("fileId", file.legacyFileId());
                    generator.writeStringField("fileName", file.fileName());
                    generator.writeNumberField("fileSize", file.byteSize());
                    generator.writeBooleanField("cleared", false);
                    generator.writeStringField("clearReason", "");
                    generator.writeStringField("createdAt", V1_UTC.format(file.createdAt()));
                    generator.writeEndObject();
                }
                generator.writeEndArray();
                generator.writeNumberField("usedFileSpace", read.files().usedFileSpace());
                generator.writeNumberField("maxFileSpace", read.files().maxFileSpace());
            } else {
                LegacyV1RoomFilesResult.Rejected rejected =
                        (LegacyV1RoomFilesResult.Rejected) result;
                generator.writeBooleanField("success", false);
                if (requestedRoomId > 0 && requestedRoomId <= Integer.MAX_VALUE) {
                    generator.writeNumberField("roomId", requestedRoomId);
                }
                generator.writeStringField("errorCode", rejected.name());
                generator.writeStringField("error", "您没有管理员权限");
            }
            generator.writeEndObject();
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 room files encoding failed", exception);
        }
        byte[] encoded = output.toByteArray();
        if (encoded.length > MAX_RESPONSE_BYTES) {
            throw new IllegalStateException("V1 room files response exceeded wire bound");
        }
        return encoded;
    }

    private static DecodedRequest malformedIfOwned(String type) {
        return "ROOM_FILES_REQ".equals(type) ? malformed() : other();
    }

    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED, 0);
    }

    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, 0);
    }
}
