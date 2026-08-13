package com.fallingnight.chat.application.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ConversationEventOutboxStatusTest {
    @Test
    void reportsAgeWithoutFailingOnSmallClockSkew() {
        Instant created = Instant.parse("2030-01-01T00:00:10Z");
        var status = new ConversationEventOutboxStatus(
                1, 1, 0, 0, 0, 0, Optional.of(created));
        assertEquals(5, status.oldestAgeSeconds(created.plusSeconds(5)));
        assertEquals(0, status.oldestAgeSeconds(created.minusSeconds(1)));
    }

    @Test
    void requiresOldestTimestampExactlyWhenBacklogExists() {
        assertThrows(IllegalArgumentException.class, () ->
                new ConversationEventOutboxStatus(1, 0, 0, 0, 0, 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () ->
                new ConversationEventOutboxStatus(0, 0, 0, 0, 0, 0,
                        Optional.of(Instant.EPOCH)));
    }
}
