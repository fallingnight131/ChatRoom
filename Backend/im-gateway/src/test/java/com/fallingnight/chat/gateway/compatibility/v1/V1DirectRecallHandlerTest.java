package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectRecallResult;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class V1DirectRecallHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test void bindsActorAndRoutesOnlyFirstRecall() {
        UUID actor = UUID.randomUUID(), target = UUID.randomUUID();
        var identity = identity(actor);
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel peer = new EmbeddedChannel(); registry.replace(target, peer);
        EmbeddedChannel sender = new EmbeddedChannel(new V1DirectRecallHandler(command -> {
            if (!command.actorAccountId().equals(actor) || command.legacyMessageId() != 101)
                throw new AssertionError();
            return new LegacyV1DirectRecallResult.Recalled(
                    false, 9, 101, 4, NOW, target, "peer");
        }, new V1JsonDirectRecallCodec(Clock.fixed(NOW, ZoneOffset.UTC)), registry,
                Runnable::run, V1DirectRecallEventSink.noop()));
        try {
            sender.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity);
            sender.writeInbound(request()); sender.runPendingTasks();
            TextWebSocketFrame response = sender.readOutbound();
            try { assertTrue(response.text().contains("\"mutationSequence\":4")); }
            finally { response.release(); }
            peer.runPendingTasks(); TextWebSocketFrame notification = peer.readOutbound();
            try { assertTrue(notification.text().contains("\"friendUsername\":\"owner\"")); }
            finally { notification.release(); }
        } finally { sender.finishAndReleaseAll(); peer.finishAndReleaseAll(); }
    }

    @Test void duplicateSuppressesNotificationAndMalformedCloses() {
        UUID target = UUID.randomUUID(); V1AccountConnectionRegistry registry =
                new V1AccountConnectionRegistry();
        EmbeddedChannel peer = new EmbeddedChannel(); registry.replace(target, peer);
        EmbeddedChannel sender = channel(registry, target, true);
        try {
            sender.writeInbound(request()); sender.runPendingTasks();
            ((TextWebSocketFrame) sender.readOutbound()).release();
            peer.runPendingTasks(); assertNull(peer.readOutbound());
        } finally { sender.finishAndReleaseAll(); peer.finishAndReleaseAll(); }
        EmbeddedChannel malformed = channel(new V1AccountConnectionRegistry(), target, false);
        try {
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"FRIEND_RECALL_REQ\",\"data\":{\"messageId\":1.5}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(
            V1AccountConnectionRegistry registry, UUID target, boolean duplicate) {
        UUID actor = UUID.randomUUID();
        EmbeddedChannel channel = new EmbeddedChannel(new V1DirectRecallHandler(command ->
                new LegacyV1DirectRecallResult.Recalled(duplicate, 9, 101, 4, NOW,
                        target, "peer"),
                new V1JsonDirectRecallCodec(Clock.fixed(NOW, ZoneOffset.UTC)), registry,
                Runnable::run, V1DirectRecallEventSink.noop()));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor)); return channel;
    }
    private static LegacyV1AuthenticatedIdentity identity(UUID actor) {
        return new LegacyV1AuthenticatedIdentity(1, actor, UUID.randomUUID(), UUID.randomUUID(),
                NOW.plusSeconds(60), "owner", "Owner", false);
    }
    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame("{\"type\":\"FRIEND_RECALL_REQ\",\"data\":{"
                + "\"messageId\":101,\"friendUsername\":\"spoofed\"}}");
    }
}
