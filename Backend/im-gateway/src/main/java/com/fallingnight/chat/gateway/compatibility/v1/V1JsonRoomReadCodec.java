package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import java.io.IOException;

/** Strict bounded classifier for response-free V1 MARK_ROOM_READ. */
public final class V1JsonRoomReadCodec {
    public enum RequestKind { MARK_READ, MALFORMED_MARK_READ, OTHER }
    public record DecodedRequest(RequestKind kind, long legacyRoomId) { }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > 4096) return other();
        String type = null; Long roomId = null; boolean validData = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return malformedIfMark(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other(); type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    Long parsed = readData(parser); if (parsed == null) return malformedIfMark(type);
                    roomId = parsed; validData = true;
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return malformedIfMark(type);
        } catch (IOException | RuntimeException exception) { return malformedIfMark(type); }
        if (!"MARK_ROOM_READ".equals(type)) return other();
        return validData ? new DecodedRequest(RequestKind.MARK_READ, roomId) : malformed();
    }
    private static Long readData(JsonParser parser) throws IOException {
        Long roomId = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            if ("roomId".equals(field) && value == JsonToken.VALUE_NUMBER_INT) {
                try { roomId = parser.getLongValue(); } catch (RuntimeException e) { invalid = true; }
            } else invalid = true;
            if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) parser.skipChildren();
        }
        return invalid ? null : roomId;
    }
    private static DecodedRequest malformedIfMark(String type) {
        return "MARK_ROOM_READ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED_MARK_READ, 0);
    }
    private static DecodedRequest other() { return new DecodedRequest(RequestKind.OTHER, 0); }
}
