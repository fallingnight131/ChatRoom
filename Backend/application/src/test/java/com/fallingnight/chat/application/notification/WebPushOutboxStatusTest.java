package com.fallingnight.chat.application.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WebPushOutboxStatusTest {
    @Test
    void validatesCompleteIdentityFreeCategoriesAndComputesBoundedAge() {
        Instant committed = Instant.parse("2026-08-17T00:00:00Z");
        WebPushOutboxStatus status = new WebPushOutboxStatus(
                10, 3, 2, 4, 1, 5, 7, Optional.of(committed));

        assertEquals(10, status.pending());
        assertEquals(60, status.oldestAgeSeconds(committed.plusSeconds(60)));
        assertEquals(0, status.oldestAgeSeconds(committed.minusSeconds(1)));
        assertEquals(0, new WebPushOutboxStatus(
                0, 0, 0, 0, 0, 0, 0, Optional.empty())
                .oldestAgeSeconds(committed));
    }

    @Test
    void rejectsIncompleteOrInconsistentSnapshots() {
        Instant committed = Instant.parse("2026-08-17T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new WebPushOutboxStatus(
                2, 1, 0, 0, 0, 0, 0, Optional.of(committed)));
        assertThrows(IllegalArgumentException.class, () -> new WebPushOutboxStatus(
                1, 1, 0, 0, 0, 2, 1, Optional.of(committed)));
        assertThrows(IllegalArgumentException.class, () -> new WebPushOutboxStatus(
                0, 0, 0, 0, 0, 0, 1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new WebPushOutboxStatus(
                1, 1, 0, 0, 0, 0, 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new WebPushOutboxStatus(
                Long.MAX_VALUE, Long.MAX_VALUE, 1, 0, 0, 0, 1,
                Optional.of(committed)));
    }
}
