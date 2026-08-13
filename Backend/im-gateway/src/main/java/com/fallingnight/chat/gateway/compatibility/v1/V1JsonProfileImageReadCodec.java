package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.profile.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded V1 user/room avatar read codec; Base64 exists only at this edge. */
public final class V1JsonProfileImageReadCodec {
    public static final int MAX_REQUEST_BYTES = 4096;
    public static final int MAX_RESPONSE_BYTES = 384 * 1024;
    public enum RequestKind { ACCOUNT, ROOM, MALFORMED, OTHER }
    public record DecodedRequest(RequestKind kind, String username, long roomId) { }

    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(512).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;

    public V1JsonProfileImageReadCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > MAX_REQUEST_BYTES) return other();
        String type = null; Data data = null; boolean invalidData = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return owned(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    data = readData(parser); invalidData = data == null;
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return owned(type);
        } catch (IOException | RuntimeException exception) { return owned(type); }
        if ("AVATAR_GET_REQ".equals(type)) {
            if (invalidData || data == null || data.username() == null || data.roomId() != null)
                return malformed();
            return new DecodedRequest(RequestKind.ACCOUNT, data.username(), 0);
        }
        if ("ROOM_AVATAR_GET_REQ".equals(type)) {
            if (invalidData || data == null || data.roomId() == null || data.username() != null
                    || data.roomId() <= 0 || data.roomId() > Integer.MAX_VALUE)
                return malformed();
            return new DecodedRequest(RequestKind.ROOM, null, data.roomId());
        }
        return other();
    }

    private static Data readData(JsonParser parser) throws IOException {
        String username = null; Long roomId = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            if ("username".equals(field) && value == JsonToken.VALUE_STRING)
                username = parser.getText();
            else if ("roomId".equals(field) && value == JsonToken.VALUE_NUMBER_INT)
                roomId = parser.getLongValue();
            else { invalid = true; parser.skipChildren(); }
        }
        return invalid ? null : new Data(username, roomId);
    }

    public byte[] encodeResponse(DecodedRequest request, ProfileImageLoadResult result) {
        Objects.requireNonNull(request, "request"); Objects.requireNonNull(result, "result");
        if (request.kind() != RequestKind.ACCOUNT && request.kind() != RequestKind.ROOM)
            throw new IllegalArgumentException("profile image response needs an owned request");
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                result instanceof ProfileImageLoadResult.Loaded loaded
                        ? loaded.payload().byteSize() * 4 / 3 + 512 : 512);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type",
                    request.kind() == RequestKind.ACCOUNT
                            ? "AVATAR_GET_RSP" : "ROOM_AVATAR_GET_RSP");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            if (request.kind() == RequestKind.ACCOUNT)
                generator.writeStringField("username", request.username());
            else generator.writeNumberField("roomId", request.roomId());
            boolean found = result instanceof ProfileImageLoadResult.Loaded;
            generator.writeBooleanField("success", found);
            if (result instanceof ProfileImageLoadResult.Loaded loaded) {
                String base64 = loaded.payload().withCopy(
                        bytes -> Base64.getEncoder().encodeToString(bytes));
                generator.writeStringField("avatarData", base64);
                generator.writeNumberField("width", loaded.width());
                generator.writeNumberField("height", loaded.height());
                generator.writeNumberField("version", loaded.version());
                generator.writeNumberField("updatedAt", loaded.updatedAt().toEpochMilli());
            }
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 profile image encoding failed", exception);
        }
        byte[] encoded = output.toByteArray();
        if (encoded.length > MAX_RESPONSE_BYTES)
            throw new IllegalStateException("V1 profile image response exceeded wire bound");
        return encoded;
    }

    private static DecodedRequest owned(String type) {
        return "AVATAR_GET_REQ".equals(type) || "ROOM_AVATAR_GET_REQ".equals(type)
                ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED, null, 0);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, null, 0);
    }
    private record Data(String username, Long roomId) { }
}
