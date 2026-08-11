package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

class GatewayConnectionLimitHandlerTest {
    @Test
    void rejectsAboveCapAndReleasesSlotOnClose() {
        GatewayConnectionLimiter limiter = new GatewayConnectionLimiter(1);
        EmbeddedChannel accepted = new EmbeddedChannel(new GatewayConnectionLimitHandler(limiter));
        EmbeddedChannel rejected = new EmbeddedChannel(new GatewayConnectionLimitHandler(limiter));
        try {
            assertTrue(accepted.isActive());
            assertFalse(rejected.isActive());
            assertEquals(1, limiter.activeConnections());

            accepted.close();
            assertEquals(0, limiter.activeConnections());

            EmbeddedChannel recovered = new EmbeddedChannel(
                    new GatewayConnectionLimitHandler(limiter));
            try {
                assertTrue(recovered.isActive());
                assertEquals(1, limiter.activeConnections());
            } finally {
                recovered.finishAndReleaseAll();
            }
            assertEquals(0, limiter.activeConnections());
        } finally {
            accepted.finishAndReleaseAll();
            rejected.finishAndReleaseAll();
        }
    }
}
