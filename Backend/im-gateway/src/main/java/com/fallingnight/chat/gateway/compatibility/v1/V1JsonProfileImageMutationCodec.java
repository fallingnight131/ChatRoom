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

/** Strict V1 avatar upload codec; decoded bytes are bounded, owned, and clearable. */
public final class V1JsonProfileImageMutationCodec {
    public static final int MAX_BASE64_CHARS =
            ((LegacyV1AvatarUpload.MAX_BYTES + 2) / 3) * 4;
    public static final int MAX_REQUEST_BYTES = 384 * 1024;
    public static final int MAX_RESPONSE_BYTES = 4096;
    public static final int MAX_NOTIFICATION_BYTES = 384 * 1024;
    public enum RequestKind { ACCOUNT, ROOM, MALFORMED, OTHER }
    public record DecodedRequest(RequestKind kind, long roomId,
            LegacyV1AvatarUpload upload) implements AutoCloseable {
        @Override public void close() { if (upload != null) upload.close(); }
    }

    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(MAX_BASE64_CHARS)
                    .maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private final Clock clock;

    public V1JsonProfileImageMutationCodec(Clock clock) {
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
        RequestKind kind;
        if ("AVATAR_UPLOAD_REQ".equals(type)) kind = RequestKind.ACCOUNT;
        else if ("ROOM_AVATAR_UPLOAD_REQ".equals(type)) kind = RequestKind.ROOM;
        else return other();
        if (invalidData || data == null || data.avatarData() == null
                || (kind == RequestKind.ACCOUNT && data.roomId() != null)
                || (kind == RequestKind.ROOM && (data.roomId() == null
                    || data.roomId() <= 0 || data.roomId() > Integer.MAX_VALUE)))
            return malformed();
        LegacyV1AvatarUpload upload = decodeCanonicalBase64(data.avatarData());
        if (upload == null) return malformed();
        return new DecodedRequest(kind,
                kind == RequestKind.ROOM ? data.roomId() : 0, upload);
    }

    private static Data readData(JsonParser parser) throws IOException {
        Long roomId = null; String avatarData = null; boolean invalid = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String field = parser.currentName(); JsonToken value = parser.nextToken();
            if ("roomId".equals(field) && value == JsonToken.VALUE_NUMBER_INT)
                roomId = parser.getLongValue();
            else if ("avatarData".equals(field) && value == JsonToken.VALUE_STRING)
                avatarData = parser.getText();
            else { invalid = true; parser.skipChildren(); }
        }
        return invalid ? null : new Data(roomId, avatarData);
    }

    private static LegacyV1AvatarUpload decodeCanonicalBase64(String encoded) {
        if (encoded.isEmpty() || encoded.length() > MAX_BASE64_CHARS) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            try {
                if (decoded.length == 0 || decoded.length > LegacyV1AvatarUpload.MAX_BYTES
                        || !Base64.getEncoder().encodeToString(decoded).equals(encoded))
                    return null;
                return LegacyV1AvatarUpload.copyOf(decoded);
            } finally { java.util.Arrays.fill(decoded, (byte) 0); }
        } catch (IllegalArgumentException exception) { return null; }
    }

    public byte[] encodeResponse(DecodedRequest request, ProfileImageMutationResult result) {
        Objects.requireNonNull(request, "request"); Objects.requireNonNull(result, "result");
        return encode(request.kind() == RequestKind.ACCOUNT
                ? "AVATAR_UPLOAD_RSP" : "ROOM_AVATAR_UPLOAD_RSP",
                MAX_RESPONSE_BYTES, generator -> {
                    if (request.kind() == RequestKind.ROOM)
                        generator.writeNumberField("roomId", request.roomId());
                    boolean success = result instanceof ProfileImageMutationResult.Committed;
                    generator.writeBooleanField("success", success);
                    if (result instanceof ProfileImageMutationResult.Committed committed) {
                        generator.writeNumberField("version", committed.metadata().version());
                        generator.writeBooleanField("changed", committed.metadata().changed());
                        generator.writeNumberField("updatedAt",
                                committed.metadata().updatedAt().toEpochMilli());
                    } else {
                        var rejected = (ProfileImageMutationResult.Rejected) result;
                        generator.writeStringField("errorCode", rejected.name());
                        generator.writeStringField("error", switch (rejected) {
                            case INVALID_IMAGE -> "头像必须是有效且不超过限制的PNG图片";
                            case ROOM_ADMIN_REQUIRED -> "只有管理员可以修改聊天室头像";
                            case ACCOUNT_UNAVAILABLE, OBJECT_EVIDENCE_CONFLICT -> "保存头像失败";
                        });
                    }
                });
    }

    public byte[] encodeNotification(DecodedRequest request, String username,
            ProfileImageMutationResult.Committed committed) {
        Objects.requireNonNull(request, "request"); Objects.requireNonNull(committed, "committed");
        if (!committed.metadata().changed())
            throw new IllegalArgumentException("unchanged image has no notification");
        ProfileImageObjectPayload payload = committed.notificationPayload().orElseThrow();
        return encode(request.kind() == RequestKind.ACCOUNT
                ? "AVATAR_UPDATE_NOTIFY" : "ROOM_AVATAR_UPDATE_NOTIFY",
                MAX_NOTIFICATION_BYTES, generator -> {
                    if (request.kind() == RequestKind.ACCOUNT)
                        generator.writeStringField("username",
                                Objects.requireNonNull(username, "username"));
                    else generator.writeNumberField("roomId", request.roomId());
                    generator.writeStringField("avatarData", payload.withCopy(
                            bytes -> Base64.getEncoder().encodeToString(bytes)));
                    generator.writeNumberField("version", committed.metadata().version());
                    generator.writeNumberField("updatedAt",
                            committed.metadata().updatedAt().toEpochMilli());
                });
    }

    private byte[] encode(String type, int maxBytes, Fields fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject(); generator.writeStringField("type", type);
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data"); fields.write(generator);
            generator.writeEndObject(); generator.writeEndObject();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
        byte[] encoded = output.toByteArray();
        if (encoded.length > maxBytes)
            throw new IllegalStateException("V1 profile image mutation frame too large");
        return encoded;
    }

    private static DecodedRequest owned(String type) {
        return "AVATAR_UPLOAD_REQ".equals(type) || "ROOM_AVATAR_UPLOAD_REQ".equals(type)
                ? malformed() : other();
    }
    private static DecodedRequest malformed() {
        return new DecodedRequest(RequestKind.MALFORMED, 0, null);
    }
    private static DecodedRequest other() {
        return new DecodedRequest(RequestKind.OTHER, 0, null);
    }
    private record Data(Long roomId, String avatarData) { }
    @FunctionalInterface private interface Fields {
        void write(JsonGenerator generator) throws IOException;
    }
}
