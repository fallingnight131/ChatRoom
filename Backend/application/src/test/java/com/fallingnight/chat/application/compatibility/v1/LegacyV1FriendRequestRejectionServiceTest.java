package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class LegacyV1FriendRequestRejectionServiceTest {
    @Test
    void bindsRecipientAndRejectsInvalidLegacyIdsBeforePersistence() {
        UUID recipient = UUID.randomUUID();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        LegacyV1FriendRequestRejectionService service =
                new LegacyV1FriendRequestRejectionService((requestId, accountId) -> {
                    calls.incrementAndGet();
                    assertEquals(70, requestId);
                    assertEquals(recipient, accountId);
                    return new LegacyV1FriendRequestRejectionResult.Accepted(false);
                });

        assertEquals(new LegacyV1FriendRequestRejectionResult.Accepted(false),
                service.reject(recipient, 70));
        assertEquals(LegacyV1FriendRequestRejectionResult.Rejected.INSTANCE,
                service.reject(recipient, 0));
        assertEquals(1, calls.get());
    }
}
