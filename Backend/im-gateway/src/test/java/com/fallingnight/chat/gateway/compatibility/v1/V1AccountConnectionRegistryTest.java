package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class V1AccountConnectionRegistryTest {
    @Test
    void reportsOnlyRequestedActiveAuthoritativeConnections() {
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        UUID activeId = UUID.randomUUID();
        UUID closedId = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        EmbeddedChannel active = new EmbeddedChannel();
        EmbeddedChannel closed = new EmbeddedChannel();
        try {
            registry.replace(activeId, active);
            registry.replace(closedId, closed);
            closed.close();
            closed.runPendingTasks();

            assertEquals(Set.of(activeId),
                    registry.onlineAccounts(Set.of(activeId, closedId, unknown)));
            assertEquals(Set.of(), registry.onlineAccounts(Set.of(unknown)));
        } finally {
            active.finishAndReleaseAll();
            closed.finishAndReleaseAll();
        }
    }

    @Test
    void executesOnlyOnTheCurrentActiveConnection() {
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        UUID accountId = UUID.randomUUID();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel replacement = new EmbeddedChannel();
        try {
            registry.replace(accountId, first);
            var invoked = new java.util.concurrent.atomic.AtomicInteger();
            assertTrue(registry.executeIfActive(accountId, ignored -> invoked.incrementAndGet()));
            first.runPendingTasks();
            assertEquals(1, invoked.get());

            registry.replace(accountId, replacement);
            assertTrue(registry.executeIfActive(accountId, ignored -> invoked.incrementAndGet()));
            replacement.runPendingTasks();
            assertEquals(2, invoked.get());
            first.close();
            first.runPendingTasks();
            assertEquals(Set.of(accountId), registry.onlineAccounts(Set.of(accountId)));

            replacement.close();
            replacement.runPendingTasks();
            assertFalse(registry.executeIfActive(accountId, ignored -> invoked.incrementAndGet()));
            assertEquals(2, invoked.get());
        } finally {
            first.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }
}
