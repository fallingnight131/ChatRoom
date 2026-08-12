package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LegacyV1FriendRequestCreationServiceTest {
    @Test
    void bindsRequesterAndRejectsInvalidTargetBeforePersistence() {
        UUID requester = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        var service = new LegacyV1FriendRequestCreationService((accountId, username) -> {
            calls.incrementAndGet();
            assertEquals(requester, accountId);
            assertEquals("peer", username);
            return new LegacyV1FriendRequestCreationResult.Accepted(false, recipient);
        });

        assertEquals(new LegacyV1FriendRequestCreationResult.Accepted(false, recipient),
                service.create(requester, "peer"));
        assertEquals(LegacyV1FriendRequestCreationResult.Rejected.INVALID_TARGET,
                service.create(requester, " peer "));
        assertEquals(LegacyV1FriendRequestCreationResult.Rejected.INVALID_TARGET,
                service.create(requester, "x".repeat(129)));
        assertEquals(1, calls.get());
    }

    @Test
    void failsClosedOnImpossibleSelfAcceptance() {
        UUID requester = UUID.randomUUID();
        var service = new LegacyV1FriendRequestCreationService((accountId, username) ->
                new LegacyV1FriendRequestCreationResult.Accepted(false, requester));
        assertThrows(IllegalStateException.class, () -> service.create(requester, "self"));
    }
}
