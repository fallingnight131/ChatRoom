package com.fallingnight.chat.application.contact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AccountBlockServiceTest {
    @Test
    void bindsAuthenticatedActorAndPreservesStableOperationIdentity() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        var service = new AccountBlockService(mutation -> {
            assertEquals(actor, mutation.actorAccountId());
            assertEquals(target, mutation.targetAccountId());
            assertEquals(operation, mutation.clientOperationId());
            assertEquals(true, mutation.blocked());
            return new AccountBlockResult.Applied(
                    actor, target, true, true, operation);
        });

        assertEquals(new AccountBlockResult.Applied(actor, target, true, true, operation),
                service.apply(actor, new AccountBlockIntent(target, true, operation)));
    }

    @Test
    void rejectsSelfBlockBeforeCallingPersistence() {
        UUID actor = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        var service = new AccountBlockService(mutation -> {
            calls.incrementAndGet();
            return AccountBlockResult.Rejected.TARGET_UNAVAILABLE;
        });

        assertEquals(AccountBlockResult.Rejected.SELF_BLOCK,
                service.apply(actor, new AccountBlockIntent(actor, true, UUID.randomUUID())));
        assertEquals(0, calls.get());
    }

    @Test
    void containsMismatchedAdapterResultsAndNullBoundaries() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        var mismatched = new AccountBlockService(mutation ->
                new AccountBlockResult.Applied(
                        actor, UUID.randomUUID(), true, true, operation));
        assertThrows(IllegalStateException.class, () -> mismatched.apply(
                actor, new AccountBlockIntent(target, true, operation)));

        var nullResult = new AccountBlockService(mutation -> null);
        assertThrows(NullPointerException.class, () -> nullResult.apply(
                actor, new AccountBlockIntent(target, false, operation)));
        assertThrows(NullPointerException.class, () ->
                new AccountBlockIntent(null, true, operation));
    }
}
