package com.fallingnight.chat.application.profile;

import java.util.Arrays;
import java.util.Objects;

/** Owned bounded bytes decoded at the V1 compatibility boundary. */
public final class LegacyV1AvatarUpload implements AutoCloseable {
    public static final int MAX_BYTES = 256 * 1024;
    private byte[] bytes;

    private LegacyV1AvatarUpload(byte[] bytes) { this.bytes = bytes; }

    public static LegacyV1AvatarUpload copyOf(byte[] source) {
        Objects.requireNonNull(source, "source");
        if (source.length == 0 || source.length > MAX_BYTES)
            throw new IllegalArgumentException("avatar bytes must be in 1..262144");
        return new LegacyV1AvatarUpload(Arrays.copyOf(source, source.length));
    }

    public synchronized <T> T withCopy(java.util.function.Function<byte[], T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (bytes == null) throw new IllegalStateException("avatar upload is closed");
        byte[] copy = Arrays.copyOf(bytes, bytes.length);
        try { return operation.apply(copy); }
        finally { Arrays.fill(copy, (byte) 0); }
    }

    public synchronized int byteSize() {
        if (bytes == null) throw new IllegalStateException("avatar upload is closed");
        return bytes.length;
    }

    @Override public synchronized void close() {
        if (bytes != null) { Arrays.fill(bytes, (byte) 0); bytes = null; }
    }
}
