package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class V1AuthenticationTimeoutHandlerTest {
    @Test
    void closesUnauthenticatedConnectionAtFixedDeadline() {
        EmbeddedChannel channel = channel();
        try {
            advance(channel, 99);
            assertTrue(channel.isActive());
            assertNull(channel.readOutbound());

            advance(channel, 1);
            CloseWebSocketFrame close = channel.readOutbound();
            try {
                assertEquals(WebSocketCloseStatus.POLICY_VIOLATION.code(), close.statusCode());
                assertEquals("V1 authentication timeout", close.reasonText());
            } finally {
                close.release();
            }
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void authenticationSignalCancelsDeadline() {
        EmbeddedChannel channel = channel();
        try {
            channel.pipeline().fireUserEventTriggered(V1ConnectionPhaseEvent.AUTHENTICATED);
            advance(channel, 500);
            assertTrue(channel.isActive());
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsSubMillisecondDeadline() {
        try {
            new V1AuthenticationTimeoutHandler(Duration.ofNanos(1));
        } catch (IllegalArgumentException expected) {
            assertEquals("timeout must be at least 1 ms", expected.getMessage());
            return;
        }
        throw new AssertionError("expected deadline validation failure");
    }

    private static EmbeddedChannel channel() {
        return new EmbeddedChannel(new V1AuthenticationTimeoutHandler(Duration.ofMillis(100)));
    }

    private static void advance(EmbeddedChannel channel, long millis) {
        channel.advanceTimeBy(millis, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();
    }
}
