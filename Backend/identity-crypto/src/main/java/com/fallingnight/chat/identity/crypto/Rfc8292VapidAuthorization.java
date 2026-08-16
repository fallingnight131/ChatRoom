package com.fallingnight.chat.identity.crypto;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/** Owned RFC 8292 Authorization value. Callers receive only temporary copies. */
public final class Rfc8292VapidAuthorization implements AutoCloseable {
    private byte[] ascii;

    Rfc8292VapidAuthorization(byte[] ascii) {
        this.ascii = Objects.requireNonNull(ascii, "ascii").clone();
    }

    public synchronized <T> T withAsciiCopy(Function<byte[], T> action) {
        Objects.requireNonNull(action, "action");
        if (ascii == null) throw new IllegalStateException("VAPID authorization is closed");
        byte[] copy = ascii.clone();
        try { return action.apply(copy); }
        finally { Arrays.fill(copy, (byte) 0); }
    }

    public synchronized boolean isClosed() {
        return ascii == null;
    }

    @Override
    public synchronized void close() {
        if (ascii != null) Arrays.fill(ascii, (byte) 0);
        ascii = null;
    }

    @Override
    public String toString() {
        return "Rfc8292VapidAuthorization[value=REDACTED]";
    }
}
