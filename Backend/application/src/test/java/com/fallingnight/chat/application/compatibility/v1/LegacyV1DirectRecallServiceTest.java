package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LegacyV1DirectRecallServiceTest {
    @Test
    void bindsActorAndRejectsInvalidMessageIdentityBeforePersistence() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        var service = new LegacyV1DirectRecallService(command -> {
            calls.incrementAndGet();
            assertEquals(actor, command.actorAccountId());
            assertEquals(101, command.legacyMessageId());
            return new LegacyV1DirectRecallResult.Recalled(
                    false, 9, 101, 4, Instant.EPOCH, target, "peer");
        });

        assertEquals(new LegacyV1DirectRecallResult.Recalled(
                        false, 9, 101, 4, Instant.EPOCH, target, "peer"),
                service.recall(new LegacyV1DirectRecallCommand(actor, 101)));
        assertEquals(LegacyV1DirectRecallResult.Rejected.INVALID_MESSAGE_ID,
                service.recall(new LegacyV1DirectRecallCommand(actor, 0)));
        assertEquals(LegacyV1DirectRecallResult.Rejected.INVALID_MESSAGE_ID,
                service.recall(new LegacyV1DirectRecallCommand(
                        actor, (long) Integer.MAX_VALUE + 1)));
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsAResultForAnotherMessage() {
        UUID actor = UUID.randomUUID();
        var service = new LegacyV1DirectRecallService(command ->
                new LegacyV1DirectRecallResult.Recalled(false, 9, 102, 4,
                        Instant.EPOCH, UUID.randomUUID(), "peer"));

        assertThrows(IllegalStateException.class, () -> service.recall(
                new LegacyV1DirectRecallCommand(actor, 101)));
    }

    @Test
    void validatesSuccessfulProjection() {
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1DirectRecallResult.Recalled(
                        false, 0, 1, 2, Instant.EPOCH, UUID.randomUUID(), "peer"));
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1DirectRecallResult.Recalled(
                        false, 1, 1, 0, Instant.EPOCH, UUID.randomUUID(), "peer"));
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1DirectRecallResult.Recalled(
                        false, 1, 1, 2, Instant.EPOCH, UUID.randomUUID(), ""));
    }
}
