package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class LegacyV1PendingFriendRequestServiceTest {
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test
    void returnsCompleteOrderedPortResultForServerBoundAccount() {
        var request = new LegacyV1PendingFriendRequest(7, 8, "alice", "Alice", NOW);
        LegacyV1PendingFriendRequestService service = new LegacyV1PendingFriendRequestService(
                (accountId, maximum) -> {
                    assertEquals(ACCOUNT, accountId);
                    assertEquals(1_000, maximum);
                    return List.of(request);
                });
        assertEquals(List.of(request), service.listPending(ACCOUNT));
    }

    @Test
    void rejectsDuplicateIdentifiersInsteadOfReturningAmbiguousActions() {
        var first = new LegacyV1PendingFriendRequest(7, 8, "alice", "Alice", NOW);
        var duplicate = new LegacyV1PendingFriendRequest(7, 9, "bob", "Bob", NOW);
        LegacyV1PendingFriendRequestService service = new LegacyV1PendingFriendRequestService(
                (ignored, maximum) -> List.of(first, duplicate));
        assertThrows(IllegalStateException.class, () -> service.listPending(ACCOUNT));
    }
}
