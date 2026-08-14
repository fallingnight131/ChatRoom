package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LinuxResidentMemoryIntegrationTest {
    @Test
    void currentProcessProcStatusProducesPositiveCachedResidentMemory() {
        assumeTrue(System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("linux"));

        ResidentMemorySource source = ResidentMemorySources.forCurrentPlatform()
                .orElseThrow(() -> new AssertionError(
                        "Linux must select the reviewed /proc resident-memory provider"));
        try (ResidentMemorySampler sampler = ResidentMemorySampler.start(
                Optional.of(source), Duration.ofMillis(250))) {
            ResidentMemorySnapshot snapshot = sampler.snapshot();
            assertTrue(snapshot.available());
            assertTrue(snapshot.residentBytes() > 0);
            assertEquals(0, snapshot.readFailures());
            assertTrue(snapshot.sampleAgeMillis() >= 0);
        }
    }
}
