package com.fallingnight.chat.application.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SecretBytesTest {
    @Test
    void clearsEveryCallbackCopyAndRefusesUseAfterClose() {
        SecretBytes secret = SecretBytes.copyOf(new byte[] {1, 2, 3});
        AtomicReference<byte[]> retainedCopy = new AtomicReference<>();

        int length = secret.withCopy(value -> {
            retainedCopy.set(value);
            return value.length;
        });

        assertEquals(3, length);
        assertArrayEquals(new byte[] {0, 0, 0}, retainedCopy.get());
        secret.close();
        assertTrue(secret.isClosed());
        assertThrows(IllegalStateException.class,
                () -> secret.withCopy(value -> value.length));
    }
}
