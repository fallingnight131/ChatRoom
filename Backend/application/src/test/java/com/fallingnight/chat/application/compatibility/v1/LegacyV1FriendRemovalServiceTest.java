package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LegacyV1FriendRemovalServiceTest {
    @Test
    void bindsActorAndRejectsInvalidTargetBeforePersistence() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        var service = new LegacyV1FriendRemovalService((accountId, username) -> {
            calls.incrementAndGet();
            assertEquals(actor, accountId);
            assertEquals("peer", username);
            return new LegacyV1FriendRemovalResult.Removed(false, target, username);
        });

        assertEquals(new LegacyV1FriendRemovalResult.Removed(false, target, "peer"),
                service.remove(actor, "peer"));
        assertEquals(LegacyV1FriendRemovalResult.Rejected.INVALID_TARGET,
                service.remove(actor, " peer "));
        assertEquals(LegacyV1FriendRemovalResult.Rejected.INVALID_TARGET,
                service.remove(actor, "x".repeat(129)));
        assertEquals(LegacyV1FriendRemovalResult.Rejected.INVALID_TARGET,
                service.remove(actor, "peer\n"));
        assertEquals(1, calls.get());
    }

    @Test
    void failsClosedOnImpossibleSelfRemovalResult() {
        UUID actor = UUID.randomUUID();
        var service = new LegacyV1FriendRemovalService((accountId, username) ->
                new LegacyV1FriendRemovalResult.Removed(false, actor, username));

        assertThrows(IllegalStateException.class, () -> service.remove(actor, "self"));
    }
}
