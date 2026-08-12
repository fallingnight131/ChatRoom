package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LegacyV1DirectReadServiceTest {
    @Test void validatesFriendshipAndPreservesAuthenticatedActor() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID(), target = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        var service = new LegacyV1DirectReadService(command -> {
            calls.incrementAndGet(); assertEquals(actor, command.actorAccountId());
            assertEquals(9, command.legacyFriendshipId());
            return new LegacyV1DirectReadResult.Marked(
                    conversation, 9, 2, 5, true, 101, target, "peer");
        });
        assertEquals(101, ((LegacyV1DirectReadResult.Marked) service.markRead(
                new LegacyV1DirectReadCommand(actor, 9))).legacyLastReadMessageId());
        assertEquals(LegacyV1DirectReadResult.Rejected.INVALID_FRIENDSHIP_ID,
                service.markRead(new LegacyV1DirectReadCommand(actor, 0)));
        assertEquals(1, calls.get());
    }

    @Test void rejectsInconsistentIdentityAndCursorFlag() {
        UUID actor = UUID.randomUUID(), target = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> new LegacyV1DirectReadService(command ->
                new LegacyV1DirectReadResult.Marked(UUID.randomUUID(), 10,
                        1, 2, true, 3, target, "peer"))
                .markRead(new LegacyV1DirectReadCommand(actor, 9)));
        assertThrows(IllegalArgumentException.class, () -> new LegacyV1DirectReadResult.Marked(
                UUID.randomUUID(), 9, 2, 2, true, 3, target, "peer"));
    }
}
