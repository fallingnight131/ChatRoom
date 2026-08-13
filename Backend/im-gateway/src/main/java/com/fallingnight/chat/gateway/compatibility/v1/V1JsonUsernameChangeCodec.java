package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1UsernameChangeResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded V1 CHANGE_UID codec; UID remains a mutable login name. */
public final class V1JsonUsernameChangeCodec {
    public static final int MAX_REQUEST_BYTES = 4096;
    public enum RequestKind { CHANGE, MALFORMED, OTHER }
    public record DecodedRequest(RequestKind kind, String newUsername) { }
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(512).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;
    public V1JsonUsernameChangeCodec(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public DecodedRequest decode(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > MAX_REQUEST_BYTES) return other();
        String type = null, username = null; boolean invalidData = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return other();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return owned(type);
                String field = parser.currentName(); JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return other();
                    type = parser.getText();
                } else if ("data".equals(field) && value == JsonToken.START_OBJECT) {
                    Data data = readData(parser); invalidData = data == null;
                    username = data == null ? null : data.newUsername();
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) return owned(type);
        } catch (IOException | RuntimeException exception) { return owned(type); }
        if (!"CHANGE_UID_REQ".equals(type)) return other();
        return invalidData || username == null ? malformed()
                : new DecodedRequest(RequestKind.CHANGE, username);
    }
    private static Data readData(JsonParser parser) throws IOException {
        String username = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            if ("newUid".equals(field) && value == JsonToken.VALUE_STRING)
                username = parser.getText();
            else { invalid = true; parser.skipChildren(); }
        }
        return invalid || username == null ? null : new Data(username);
    }

    public byte[] encodeResponse(LegacyV1UsernameChangeResult result) {
        return encode("CHANGE_UID_RSP", generator -> {
            generator.writeBooleanField("success",
                    result instanceof LegacyV1UsernameChangeResult.Changed);
            if (result instanceof LegacyV1UsernameChangeResult.Changed changed) {
                generator.writeStringField("oldUid", changed.oldUsername());
                generator.writeStringField("newUid", changed.newUsername());
                generator.writeBooleanField("changed", changed.changed());
                generator.writeNumberField("changedAt", changed.changedAt().toEpochMilli());
                generator.writeNumberField("nextAllowedAt", changed.nextAllowedAt().toEpochMilli());
            } else if (result instanceof LegacyV1UsernameChangeResult.Cooldown cooldown) {
                generator.writeStringField("errorCode", "UID_CHANGE_COOLDOWN");
                generator.writeNumberField("retryAt", cooldown.retryAt().toEpochMilli());
                long seconds = Math.max(0, Duration.between(clock.instant(),
                        cooldown.retryAt()).getSeconds());
                long days = Math.max(1, (seconds + 86399) / 86400);
                generator.writeStringField("error", "每月只能修改一次ID，还需等待 "
                        + days + " 天");
            } else {
                var rejected = (LegacyV1UsernameChangeResult.Rejected) result;
                generator.writeStringField("errorCode", switch (rejected) {
                    case INVALID_INPUT -> "INVALID_UID";
                    case SAME_AS_CURRENT -> "UID_UNCHANGED";
                    case USERNAME_TAKEN -> "UID_TAKEN";
                    case ACCOUNT_UNAVAILABLE -> "ACCOUNT_UNAVAILABLE";
                });
                generator.writeStringField("error", switch (rejected) {
                    case INVALID_INPUT -> "用户ID必须为6-20位，只能包含字母、数字和下划线";
                    case SAME_AS_CURRENT -> "新ID与当前ID相同";
                    case USERNAME_TAKEN -> "该用户ID已被使用";
                    case ACCOUNT_UNAVAILABLE -> "修改用户ID失败";
                });
            }
        });
    }

    public byte[] encodeNotification(long roomId, LegacyV1UsernameChangeResult.Changed changed,
            String displayName) {
        return encode("UID_CHANGE_NOTIFY", generator -> {
            generator.writeNumberField("roomId", roomId);
            generator.writeStringField("oldUid", changed.oldUsername());
            generator.writeStringField("newUid", changed.newUsername());
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
        if (encoded.length > 4096) throw new IllegalStateException("V1 username frame too large");
        return encoded;
    }
    private static DecodedRequest owned(String type) {
        return "CHANGE_UID_REQ".equals(type) ? malformed() : other();
    }
    private static DecodedRequest malformed() { return new DecodedRequest(RequestKind.MALFORMED, null); }
    private static DecodedRequest other() { return new DecodedRequest(RequestKind.OTHER, null); }
    private record Data(String newUsername) { }
    @FunctionalInterface private interface Fields { void write(JsonGenerator generator) throws IOException; }
}
