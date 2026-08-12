package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRemovalResult;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class V1FriendRemovalHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final LegacyV1AuthenticatedIdentity ACTOR =
            new LegacyV1AuthenticatedIdentity(42, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), NOW.plusSeconds(60), "actor", "Actor", false);

    @Test
    void notifiesAuthoritativeTargetOnlyOnFirstRemoval() {
        V1AccountConnectionRegistry connections = new V1AccountConnectionRegistry();
        EmbeddedChannel target = new EmbeddedChannel();
        connections.replace(TARGET_ID, target);
        int[] calls = {0};
        EmbeddedChannel actor = new EmbeddedChannel(new V1FriendRemovalHandler(
                (account, username) -> new LegacyV1FriendRemovalResult.Removed(
                        calls[0]++ > 0, TARGET_ID, username),
                new V1JsonFriendRemovalCodec(Clock.fixed(NOW, ZoneOffset.UTC)),
                connections, Runnable::run, V1FriendRemovalEventSink.noop()));
        try {
            actor.attr(V1ConnectionAttributes.AUTHENTICATED).set(ACTOR);
            send(actor);
            assertSuccess(actor);
            target.runPendingTasks();
            TextWebSocketFrame notification = target.readOutbound();
            assertTrue(notification.text().contains("\"username\":\"actor\""));
            assertTrue(notification.text().contains("\"displayName\":\"Actor\""));
            notification.release();
            send(actor);
            assertSuccess(actor);
            target.runPendingTasks();
            assertNull(target.readOutbound());
        } finally {
            actor.finishAndReleaseAll();
            target.finishAndReleaseAll();
        }
    }

    private static void send(EmbeddedChannel channel) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"FRIEND_REMOVE_REQ\",\"data\":{\"username\":\"peer\"}}"));
    }
    private static void assertSuccess(EmbeddedChannel channel) {
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        assertTrue(response.text().contains("\"success\":true"));
        assertTrue(response.text().contains("\"username\":\"peer\""));
        response.release();
    }
}
