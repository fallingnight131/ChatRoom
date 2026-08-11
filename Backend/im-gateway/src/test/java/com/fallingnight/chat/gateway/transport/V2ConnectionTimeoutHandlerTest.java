package com.fallingnight.chat.gateway.transport;

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

class V2ConnectionTimeoutHandlerTest {
    @Test
    void closesAConnectionThatDoesNotNegotiateInTime() {
        EmbeddedChannel channel = channel();
        try {
            advance(channel, 99);
            assertTrue(channel.isActive());
            assertNull(channel.readOutbound());

            advance(channel, 1);
            assertTimeoutClose(channel, "V2 handshake timeout");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void startsAuthenticationDeadlineAfterNegotiation() {
        EmbeddedChannel channel = channel();
        try {
            channel.pipeline().fireUserEventTriggered(V2ConnectionPhaseEvent.NEGOTIATED);
            advance(channel, 199);
            assertTrue(channel.isActive());
            advance(channel, 1);
            assertTimeoutClose(channel, "V2 authentication timeout");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void cancelsAuthenticationDeadlineAfterSuccess() {
        EmbeddedChannel channel = channel();
        try {
            channel.pipeline().fireUserEventTriggered(V2ConnectionPhaseEvent.NEGOTIATED);
            channel.pipeline().fireUserEventTriggered(V2ConnectionPhaseEvent.AUTHENTICATED);
            advance(channel, 500);
            assertTrue(channel.isActive());
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel() {
        return new EmbeddedChannel(new V2ConnectionTimeoutHandler(
                Duration.ofMillis(100), Duration.ofMillis(200)));
    }

    private static void advance(EmbeddedChannel channel, long millis) {
        channel.advanceTimeBy(millis, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();
    }

    private static void assertTimeoutClose(EmbeddedChannel channel, String reason) {
        CloseWebSocketFrame close = channel.readOutbound();
        try {
            assertEquals(WebSocketCloseStatus.POLICY_VIOLATION.code(), close.statusCode());
            assertEquals(reason, close.reasonText());
        } finally {
            close.release();
        }
        assertFalse(channel.isActive());
    }
}
