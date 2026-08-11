package com.fallingnight.chat.application.security;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/** Owns secret bytes, exposes only short-lived copies, and zeroes memory on close. */
public final class SecretBytes implements AutoCloseable {
    private byte[] value;

    private SecretBytes(byte[] value) {
        this.value = value;
    }

    public static SecretBytes copyOf(byte[] value) {
        Objects.requireNonNull(value, "value");
        return new SecretBytes(value.clone());
    }

    public synchronized <T> T withCopy(Function<byte[], T> action) {
        Objects.requireNonNull(action, "action");
        if (value == null) {
            throw new IllegalStateException("secret is closed");
        }
        byte[] copy = value.clone();
        try {
            return action.apply(copy);
        } finally {
            Arrays.fill(copy, (byte) 0);
        }
    }

    public synchronized boolean isClosed() {
        return value == null;
    }

    @Override
    public synchronized void close() {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
            value = null;
        }
    }
}
