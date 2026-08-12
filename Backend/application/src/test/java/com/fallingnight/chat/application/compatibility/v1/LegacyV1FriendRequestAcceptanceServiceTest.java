package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LegacyV1FriendRequestAcceptanceServiceTest {
    @Test
    void bindsRecipientAndRejectsInvalidLegacyIdsBeforePersistence() {
        UUID recipient = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        LegacyV1FriendRequestAcceptanceService service =
                new LegacyV1FriendRequestAcceptanceService((requestId, accountId) -> {
                    calls.incrementAndGet();
                    assertEquals(70, requestId);
                    assertEquals(recipient, accountId);
                    return new LegacyV1FriendRequestAcceptanceResult.Accepted(
                            false, requester);
                });

        assertEquals(new LegacyV1FriendRequestAcceptanceResult.Accepted(false, requester),
                service.accept(recipient, 70));
        assertEquals(LegacyV1FriendRequestAcceptanceResult.Rejected.INSTANCE,
                service.accept(recipient, 0));
        assertEquals(LegacyV1FriendRequestAcceptanceResult.Rejected.INSTANCE,
                service.accept(recipient, (long) Integer.MAX_VALUE + 1));
        assertEquals(1, calls.get());
    }

    @Test
    void failsClosedOnImpossibleSelfRequestResult() {
        UUID recipient = UUID.randomUUID();
        LegacyV1FriendRequestAcceptanceService service =
                new LegacyV1FriendRequestAcceptanceService((ignoredId, ignoredAccount) ->
                        new LegacyV1FriendRequestAcceptanceResult.Accepted(
                                false, recipient));

        assertThrows(IllegalStateException.class, () -> service.accept(recipient, 70));
    }
}
