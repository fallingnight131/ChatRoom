package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

final class V1UsernameChangeHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test void refreshesLoginIdentityAndRoutesOnlyPeerEffects() {
        UUID actor = UUID.randomUUID(), peerId = UUID.randomUUID();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel peer = new EmbeddedChannel(); registry.replace(peerId, peer);
        EmbeddedChannel sender = new EmbeddedChannel(new V1UsernameChangeHandler(command -> {
            assertEquals(actor, command.actorAccountId()); assertEquals("newuser", command.newUsername());
            return new LegacyV1UsernameChangeResult.Changed(actor, "olduser", "newuser", true,
                    NOW, NOW.plus(Duration.ofDays(30)), List.of(
                        new LegacyV1UsernameChangeResult.RoomAudience(7, Set.of(peerId))));
        }, codec(), registry, Runnable::run, V1UsernameChangeEventSink.noop()));
        try {
            sender.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
            registry.replace(actor, sender); sender.writeInbound(request()); sender.runPendingTasks();
            TextWebSocketFrame response = sender.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"CHANGE_UID_RSP\""));
                assertTrue(response.text().contains("\"newUid\":\"newuser\""));
                assertTrue(response.text().contains("\"changed\":true"));
                assertFalse(response.text().contains(actor.toString()));
            } finally { response.release(); }
            assertEquals("newuser", sender.attr(V1ConnectionAttributes.AUTHENTICATED)
                    .get().username());
            sender.runPendingTasks(); assertNull(sender.readOutbound());
            peer.runPendingTasks(); TextWebSocketFrame notification = peer.readOutbound();
            try {
                assertTrue(notification.text().contains("\"type\":\"UID_CHANGE_NOTIFY\""));
                assertTrue(notification.text().contains("\"oldUid\":\"olduser\""));
                assertTrue(notification.text().contains("\"displayName\":\"Owner\""));
            } finally { notification.release(); }
        } finally { sender.finishAndReleaseAll(); peer.finishAndReleaseAll(); }
    }

    @Test void serializesCooldownAndClosesMalformedOrSaturatedRequests() {
        UUID actor = UUID.randomUUID();
        EmbeddedChannel cooldown = channel(actor, command ->
                new LegacyV1UsernameChangeResult.Cooldown(NOW.plus(Duration.ofDays(2))),
                Runnable::run);
        try {
            cooldown.writeInbound(request()); cooldown.runPendingTasks();
            TextWebSocketFrame response = cooldown.readOutbound();
            try {
                assertTrue(response.text().contains("UID_CHANGE_COOLDOWN"));
                assertTrue(response.text().contains("还需等待 2 天"));
            } finally { response.release(); }
        } finally { cooldown.finishAndReleaseAll(); }

        for (String malformed : List.of(
                "{\"type\":\"CHANGE_UID_REQ\",\"data\":{}}",
                "{\"type\":\"CHANGE_UID_REQ\",\"data\":{\"newUid\":\"newuser\",\"newUid\":\"otherid\"}}",
                "{\"type\":\"CHANGE_UID_REQ\",\"data\":{\"newUid\":\"newuser\",\"extra\":1}}")) {
            EmbeddedChannel channel = channel(actor, command -> { throw new AssertionError(); }, Runnable::run);
            try {
                channel.writeInbound(new TextWebSocketFrame(malformed));
                ((CloseWebSocketFrame) channel.readOutbound()).release(); assertFalse(channel.isActive());
            } finally { channel.finishAndReleaseAll(); }
        }
        EmbeddedChannel saturated = channel(actor, command ->
                LegacyV1UsernameChangeResult.Rejected.INVALID_INPUT,
                task -> { throw new RejectedExecutionException(); });
        try {
            saturated.writeInbound(request());
            ((CloseWebSocketFrame) saturated.readOutbound()).release(); assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(UUID actor, LegacyV1UsernameChangeUseCase useCase,
            java.util.concurrent.Executor executor) {
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel(new V1UsernameChangeHandler(
                useCase, codec(), registry, executor, V1UsernameChangeEventSink.noop()));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
        registry.replace(actor, channel); return channel;
    }
    private static V1JsonUsernameChangeCodec codec() {
        return new V1JsonUsernameChangeCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static LegacyV1AuthenticatedIdentity identity(UUID actor) {
        return new LegacyV1AuthenticatedIdentity(1, actor, UUID.randomUUID(), UUID.randomUUID(),
                NOW.plusSeconds(60), "olduser", "Owner", false);
    }
    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame("{\"type\":\"CHANGE_UID_REQ\","
                + "\"data\":{\"newUid\":\"newuser\"}}");
    }
}
