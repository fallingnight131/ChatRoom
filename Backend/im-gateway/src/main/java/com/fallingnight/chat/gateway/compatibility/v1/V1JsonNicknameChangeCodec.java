package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1NicknameChangeResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded V1 CHANGE_NICKNAME codec with compatible response/effects. */
public final class V1JsonNicknameChangeCodec {
    public static final int MAX_REQUEST_BYTES = 4096;
    public enum RequestKind { CHANGE, MALFORMED, OTHER }
    public record DecodedRequest(RequestKind kind, String displayName) { }

    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(512).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;

    public V1JsonNicknameChangeCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > MAX_REQUEST_BYTES) return other();
        String type = null; String displayName = null; boolean invalidData = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return owned(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    Data data = readData(parser);
                    invalidData = data == null;
                    displayName = data == null ? null : data.displayName();
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return owned(type);
        } catch (IOException | RuntimeException exception) { return owned(type); }
        if (!"CHANGE_NICKNAME_REQ".equals(type)) return other();
        return invalidData || displayName == null ? malformed()
                : new DecodedRequest(RequestKind.CHANGE, displayName);
    }

    private static Data readData(JsonParser parser) throws IOException {
        String displayName = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            if ("displayName".equals(field) && value == JsonToken.VALUE_STRING)
                displayName = parser.getText();
            else { invalid = true; parser.skipChildren(); }
        }
        return invalid || displayName == null ? null : new Data(displayName);
    }

    public byte[] encodeResponse(LegacyV1NicknameChangeResult result) {
        return encode("CHANGE_NICKNAME_RSP", generator -> {
            generator.writeBooleanField("success",
                    result instanceof LegacyV1NicknameChangeResult.Changed);
            if (result instanceof LegacyV1NicknameChangeResult.Changed changed) {
                generator.writeStringField("displayName", changed.newDisplayName());
                generator.writeBooleanField("changed", changed.changed());
                generator.writeNumberField("changedAt", changed.changedAt().toEpochMilli());
            } else {
                var rejected = (LegacyV1NicknameChangeResult.Rejected) result;
                generator.writeStringField("errorCode", switch (rejected) {
                    case INVALID_INPUT -> "INVALID_DISPLAY_NAME";
                    case ACCOUNT_UNAVAILABLE -> "ACCOUNT_UNAVAILABLE";
                });
                generator.writeStringField("error", switch (rejected) {
                    case INVALID_INPUT -> "昵称长度须为1-20个字符";
                    case ACCOUNT_UNAVAILABLE -> "修改昵称失败";
                });
            }
        });
    }

    public byte[] encodeNotification(long roomId, String username, String displayName) {
        return encode("NICKNAME_CHANGE_NOTIFY", generator -> {
            generator.writeNumberField("roomId", roomId);
            generator.writeStringField("username", username);
            generator.writeStringField("displayName", displayName);
        });
    }

    private byte[] encode(String type, Fields fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", type);
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data"); fields.write(generator);
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
        byte[] encoded = output.toByteArray();
        if (encoded.length > 4096) throw new IllegalStateException("V1 nickname frame too large");
        return encoded;
    }
    private static DecodedRequest owned(String type) {
        return "CHANGE_NICKNAME_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED, null);
    }
    private static DecodedRequest other() { return new DecodedRequest(RequestKind.OTHER, null); }
    private record Data(String displayName) { }
    @FunctionalInterface private interface Fields {
        void write(JsonGenerator generator) throws IOException;
    }
}
