package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestCreationResult;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class V1FriendRequestCreationHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final UUID RECIPIENT_ID = UUID.randomUUID();
    private static final LegacyV1AuthenticatedIdentity SENDER =
            new LegacyV1AuthenticatedIdentity(42, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), NOW.plusSeconds(60), "sender", "Sender", false);

    @Test
    void notifiesAuthoritativeRecipientOnlyOnFirstCreation() {
        V1AccountConnectionRegistry connections = new V1AccountConnectionRegistry();
        EmbeddedChannel recipient = new EmbeddedChannel();
        connections.replace(RECIPIENT_ID, recipient);
        int[] calls = {0};
        EmbeddedChannel sender = new EmbeddedChannel(new V1FriendRequestCreationHandler(
                (account, username) -> new LegacyV1FriendRequestCreationResult.Accepted(
                        calls[0]++ > 0, RECIPIENT_ID),
                new V1JsonFriendRequestCreationCodec(Clock.fixed(NOW, ZoneOffset.UTC)),
                connections, Runnable::run, V1FriendRequestCreationEventSink.noop()));
        try {
            sender.attr(V1ConnectionAttributes.AUTHENTICATED).set(SENDER);
            send(sender);
            assertSuccess(sender);
            recipient.runPendingTasks();
            TextWebSocketFrame notification = recipient.readOutbound();
            assertTrue(notification.text().contains("\"fromUsername\":\"sender\""));
            notification.release();
            send(sender);
            assertSuccess(sender);
            recipient.runPendingTasks();
            assertNull(recipient.readOutbound());
        } finally {
            sender.finishAndReleaseAll();
            recipient.finishAndReleaseAll();
        }
    }

    private static void send(EmbeddedChannel channel) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"FRIEND_REQUEST_REQ\",\"data\":{\"username\":\"peer\"}}"));
    }
    private static void assertSuccess(EmbeddedChannel channel) {
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        assertTrue(response.text().contains("\"success\":true"));
        response.release();
    }
}
