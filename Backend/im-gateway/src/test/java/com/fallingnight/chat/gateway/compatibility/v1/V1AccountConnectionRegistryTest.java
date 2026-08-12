package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
