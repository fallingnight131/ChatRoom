package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class V1HeartbeatHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    @Test
    void respondsToAuthenticatedHeartbeatAndConsumesAck() {
        EmbeddedChannel channel = channel();
        try {
            authenticate(channel);
            assertFalse(channel.writeInbound(frame("HEARTBEAT")));
            TextWebSocketFrame ack = channel.readOutbound();
            assertTrue(ack.text().contains("\"type\":\"HEARTBEAT_ACK\""));
            assertTrue(ack.text().contains("\"timestamp\":1786536000000"));
            ack.release();

            assertFalse(channel.writeInbound(frame("HEARTBEAT_ACK")));
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void forwardsBusinessAndMalformedFramesWithoutReinterpretingThem() {
        EmbeddedChannel channel = channel();
        try {
            authenticate(channel);
            TextWebSocketFrame business = frame("ROOM_LIST_REQ");
            assertTrue(channel.writeInbound(business));
            TextWebSocketFrame forwarded = channel.readInbound();
            assertEquals("ROOM_LIST_REQ", typeFrom(forwarded));
            forwarded.release();

            TextWebSocketFrame malformed = new TextWebSocketFrame("not-json");
            assertTrue(channel.writeInbound(malformed));
            TextWebSocketFrame malformedForwarded = channel.readInbound();
            assertEquals("not-json", malformedForwarded.text());
            malformedForwarded.release();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void closesOnlyAuthenticatedReaderIdleConnections() {
        EmbeddedChannel channel = channel();
        try {
            channel.pipeline().fireUserEventTriggered(
                    IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
            assertNull(channel.readOutbound());
            assertTrue(channel.isActive());

            authenticate(channel);
            channel.pipeline().fireUserEventTriggered(
                    IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
            CloseWebSocketFrame close = channel.readOutbound();
            assertEquals("V1 idle timeout", close.reasonText());
            close.release();
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel() {
        return new EmbeddedChannel(new V1HeartbeatHandler(new V1JsonLifecycleCodec(
                Clock.fixed(NOW, ZoneOffset.UTC))));
    }

    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(
                new LegacyV1AuthenticatedIdentity(
                        17,
                        UUID.fromString("10000000-0000-0000-0000-000000000001"),
                        UUID.fromString("20000000-0000-0000-0000-000000000002"),
                        UUID.fromString("30000000-0000-0000-0000-000000000003"),
                        NOW.plusSeconds(3600),
                        "alice",
                        "Alice",
                        false));
    }

    private static TextWebSocketFrame frame(String type) {
        return new TextWebSocketFrame(
                "{\"type\":\"" + type + "\",\"id\":\"request-1\",\"data\":{}}");
    }

    private static String typeFrom(TextWebSocketFrame frame) {
        String marker = "\"type\":\"";
        int start = frame.text().indexOf(marker) + marker.length();
        return frame.text().substring(start, frame.text().indexOf('"', start));
    }
}
