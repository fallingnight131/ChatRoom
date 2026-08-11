package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class V1WebSocketUpgradeHandlerTest {
    @Test
    void installsApplicationOnlyAfterMatchingAcceptedUpgrade() {
        ChannelInboundHandlerAdapter application = new ChannelInboundHandlerAdapter();
        EmbeddedChannel channel = new EmbeddedChannel(new V1WebSocketUpgradeHandler(
                pipeline -> pipeline.addLast("test-v1-application", application),
                Duration.ofSeconds(1)));
        try {
            assertNull(channel.pipeline().get("test-v1-application"));
            channel.attr(V1ConnectionAttributes.WEB_UPGRADE_ACCEPTED).set(true);
            channel.pipeline().fireUserEventTriggered(complete("/v1/web", "chat.v1"));

            assertNull(channel.pipeline().get(V1WebSocketUpgradeHandler.class));
            assertEquals(application, channel.pipeline().get("test-v1-application"));
            assertTrue(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsHandshakeThatDidNotPassExactGuard() {
        assertMismatch(null, "/v1/web", "chat.v1");
        assertMismatch(true, "/v2/web", "chat.v1");
        assertMismatch(true, "/v1/web", "chat.v2");
    }

    @Test
    void closesConnectionWhenUpgradeDeadlineExpires() {
        EmbeddedChannel channel = new EmbeddedChannel(new V1WebSocketUpgradeHandler(
                pipeline -> {}, Duration.ofMillis(10)));
        try {
            channel.advanceTimeBy(11, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void assertMismatch(Boolean accepted, String path, String subprotocol) {
        EmbeddedChannel channel = new EmbeddedChannel(new V1WebSocketUpgradeHandler(
                pipeline -> {
                    throw new AssertionError("mismatched upgrade installed application");
                }, Duration.ofSeconds(1)));
        try {
            if (accepted != null) {
                channel.attr(V1ConnectionAttributes.WEB_UPGRADE_ACCEPTED).set(accepted);
            }
            channel.pipeline().fireUserEventTriggered(complete(path, subprotocol));
            CloseWebSocketFrame close = channel.readOutbound();
            try {
                assertEquals(WebSocketCloseStatus.POLICY_VIOLATION.code(), close.statusCode());
                assertEquals("V1 upgrade policy mismatch", close.reasonText());
            } finally {
                close.release();
            }
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static WebSocketServerProtocolHandler.HandshakeComplete complete(
            String path, String subprotocol) {
        return new WebSocketServerProtocolHandler.HandshakeComplete(
                path, EmptyHttpHeaders.INSTANCE, subprotocol);
    }
}
