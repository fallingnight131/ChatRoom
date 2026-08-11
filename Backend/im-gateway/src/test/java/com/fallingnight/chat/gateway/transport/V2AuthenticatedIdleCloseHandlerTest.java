package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.timeout.IdleStateEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class V2AuthenticatedIdleCloseHandlerTest {
    @Test
    void ignoresPreAuthenticationIdleAndClosesAuthenticatedReaderIdle() {
        EmbeddedChannel channel = new EmbeddedChannel(new V2AuthenticatedIdleCloseHandler());
        try {
            channel.pipeline().fireUserEventTriggered(
                    IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
            assertTrue(channel.isActive());
            assertNull(channel.readOutbound());

            channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                    new AuthenticatedConnection(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
            channel.pipeline().fireUserEventTriggered(
                    IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
            CloseWebSocketFrame close = channel.readOutbound();
            assertEquals(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE.code(), close.statusCode());
            assertEquals("V2 idle timeout", close.reasonText());
            close.release();
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
