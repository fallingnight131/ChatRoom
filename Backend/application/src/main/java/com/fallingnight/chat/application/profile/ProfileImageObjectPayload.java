package com.fallingnight.chat.application.profile;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/** Owned bounded bytes loaded from private object storage. */
public final class ProfileImageObjectPayload implements AutoCloseable {
    private byte[] bytes;

    private ProfileImageObjectPayload(byte[] bytes) { this.bytes = bytes; }

    public static ProfileImageObjectPayload copyOf(byte[] source) {
        Objects.requireNonNull(source, "source");
        if (source.length == 0 || source.length > LegacyV1AvatarUpload.MAX_BYTES)
            throw new IllegalArgumentException("profile image bytes must be in 1..262144");
        return new ProfileImageObjectPayload(Arrays.copyOf(source, source.length));
    }

    public synchronized <T> T withCopy(Function<byte[], T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (bytes == null) throw new IllegalStateException("profile image payload is closed");
        byte[] copy = Arrays.copyOf(bytes, bytes.length);
        try { return operation.apply(copy); }
        finally { Arrays.fill(copy, (byte) 0); }
    }

    public synchronized int byteSize() {
        if (bytes == null) throw new IllegalStateException("profile image payload is closed");
        return bytes.length;
    }

    @Override public synchronized void close() {
        if (bytes != null) { Arrays.fill(bytes, (byte) 0); bytes = null; }
    }
}
