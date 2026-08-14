package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResidentMemorySamplerTest {
    @Test
    void cachesSuccessFailureAndRecoveryWithoutReadingOnSnapshot() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        AtomicReference<Long> bytes = new AtomicReference<>(123L);
        ResidentMemorySource source = () -> {
            Long value = bytes.get();
            if (value == null) throw new IOException("unavailable");
            return value;
        };
        try (ResidentMemorySampler sampler = new ResidentMemorySampler(source, clock::get)) {
            sampler.refresh();
            assertEquals(new ResidentMemorySnapshot(true, 123, 0, 0), sampler.snapshot());
            clock.addAndGet(25_000_000L);
            assertEquals(25, sampler.snapshot().sampleAgeMillis());

            bytes.set(null);
            sampler.refresh();
            ResidentMemorySnapshot failed = sampler.snapshot();
            assertFalse(failed.available());
            assertEquals(0, failed.residentBytes());
            assertEquals(1, failed.readFailures());

            bytes.set(456L);
            sampler.refresh();
            ResidentMemorySnapshot recovered = sampler.snapshot();
            assertTrue(recovered.available());
            assertEquals(456, recovered.residentBytes());
            assertEquals(1, recovered.readFailures());
        }
    }

    @Test
    void unsupportedSourceIsExplicitlyUnavailableAndIntervalIsBounded() {
        try (ResidentMemorySampler sampler = ResidentMemorySampler.start(
                Optional.empty(), Duration.ofMillis(250))) {
            assertFalse(sampler.snapshot().available());
        }
        assertThrows(IllegalArgumentException.class,
                () -> ResidentMemorySampler.start(
                        Optional.empty(), Duration.ofMillis(249)));
    }
}
