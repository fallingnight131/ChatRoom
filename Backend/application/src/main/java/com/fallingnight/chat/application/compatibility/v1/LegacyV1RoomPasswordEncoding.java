package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;

/** Slow verification hash plus opaque server-keyed stable idempotency tag. */
public record LegacyV1RoomPasswordEncoding(String encodedHash, String idempotencyTag) {
    public LegacyV1RoomPasswordEncoding {
        Objects.requireNonNull(encodedHash, "encodedHash");
        Objects.requireNonNull(idempotencyTag, "idempotencyTag");
        if (encodedHash.isBlank() || idempotencyTag.isBlank()
                || idempotencyTag.length() > 255) {
            throw new IllegalArgumentException("room password encoding");
        }
    }
}
