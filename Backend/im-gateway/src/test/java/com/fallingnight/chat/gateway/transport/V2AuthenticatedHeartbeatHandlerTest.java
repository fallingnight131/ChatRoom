package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class V2AuthenticatedHeartbeatHandlerTest {
    @Test
    void pingsOnlyAuthenticatedWriterIdleAndForwardsOtherIdleEvents() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new V2AuthenticatedHeartbeatHandler(),
                new V2AuthenticatedIdleCloseHandler());
        try {
            channel.pipeline().fireUserEventTriggered(
                    IdleStateEvent.FIRST_WRITER_IDLE_STATE_EVENT);
            assertNull(channel.readOutbound());

            channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                    new AuthenticatedConnection(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
            channel.pipeline().fireUserEventTriggered(
                    IdleStateEvent.FIRST_WRITER_IDLE_STATE_EVENT);
            PingWebSocketFrame ping = channel.readOutbound();
            assertTrue(ping.isFinalFragment());
            assertEquals(0, ping.content().readableBytes());
            ping.release();

            channel.pipeline().fireUserEventTriggered(
                    IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
            CloseWebSocketFrame close = channel.readOutbound();
            assertEquals("V2 idle timeout", close.reasonText());
            close.release();
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
