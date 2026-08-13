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

final class V1NicknameChangeHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test void bindsActorRefreshesIdentityAndRoutesOneEffectPerRoom() {
        UUID actor = UUID.randomUUID(), peerId = UUID.randomUUID();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel peer = new EmbeddedChannel(); registry.replace(peerId, peer);
        EmbeddedChannel sender = new EmbeddedChannel(new V1NicknameChangeHandler(command -> {
            assertEquals(actor, command.actorAccountId()); assertEquals("New Name", command.newDisplayName());
            return new LegacyV1NicknameChangeResult.Changed(actor, "Owner", "New Name", true,
                    NOW, List.of(new LegacyV1NicknameChangeResult.RoomAudience(
                            7, Set.of(actor, peerId))));
        }, codec(), registry, Runnable::run, V1NicknameChangeEventSink.noop()));
        try {
            sender.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
            registry.replace(actor, sender); sender.writeInbound(request()); sender.runPendingTasks();
            TextWebSocketFrame response = sender.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"CHANGE_NICKNAME_RSP\""));
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"changed\":true"));
                assertFalse(response.text().contains(actor.toString()));
            } finally { response.release(); }
            assertEquals("New Name", sender.attr(V1ConnectionAttributes.AUTHENTICATED)
                    .get().displayName());
            sender.runPendingTasks(); peer.runPendingTasks();
            TextWebSocketFrame own = sender.readOutbound(), other = peer.readOutbound();
            try {
                assertTrue(own.text().contains("NICKNAME_CHANGE_NOTIFY"));
                assertTrue(own.text().contains("\"roomId\":7"));
                assertTrue(other.text().contains("\"username\":\"owner\""));
                assertTrue(other.text().contains("\"displayName\":\"New Name\""));
            } finally { own.release(); other.release(); }
        } finally { sender.finishAndReleaseAll(); peer.finishAndReleaseAll(); }
    }

    @Test void unchangedDoesNotNotifyAndMalformedDuplicateOrSaturationClose() {
        UUID actor = UUID.randomUUID();
        EmbeddedChannel unchanged = channel(actor, command ->
                new LegacyV1NicknameChangeResult.Changed(actor, "Owner", "Owner", false,
                        NOW, List.of()), Runnable::run);
        try {
            unchanged.writeInbound(new TextWebSocketFrame("{\"type\":\"CHANGE_NICKNAME_REQ\","
                    + "\"data\":{\"displayName\":\"Owner\"}}"));
            unchanged.runPendingTasks(); TextWebSocketFrame response = unchanged.readOutbound();
            try { assertTrue(response.text().contains("\"changed\":false")); }
            finally { response.release(); }
            assertNull(unchanged.readOutbound());
        } finally { unchanged.finishAndReleaseAll(); }

        for (String malformed : List.of(
                "{\"type\":\"CHANGE_NICKNAME_REQ\",\"data\":{}}",
                "{\"type\":\"CHANGE_NICKNAME_REQ\",\"data\":{\"displayName\":\"a\",\"displayName\":\"b\"}}",
                "{\"type\":\"CHANGE_NICKNAME_REQ\",\"data\":{\"displayName\":\"a\",\"extra\":1}}")) {
            EmbeddedChannel channel = channel(actor, command -> { throw new AssertionError(); }, Runnable::run);
            try {
                channel.writeInbound(new TextWebSocketFrame(malformed));
                ((CloseWebSocketFrame) channel.readOutbound()).release();
                assertFalse(channel.isActive());
            } finally { channel.finishAndReleaseAll(); }
        }

        EmbeddedChannel saturated = channel(actor, command ->
                LegacyV1NicknameChangeResult.Rejected.INVALID_INPUT,
                task -> { throw new RejectedExecutionException(); });
        try {
            saturated.writeInbound(request());
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(UUID actor, LegacyV1NicknameChangeUseCase useCase,
            java.util.concurrent.Executor executor) {
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel(new V1NicknameChangeHandler(
                useCase, codec(), registry, executor, V1NicknameChangeEventSink.noop()));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
        registry.replace(actor, channel); return channel;
    }
    private static V1JsonNicknameChangeCodec codec() {
        return new V1JsonNicknameChangeCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static LegacyV1AuthenticatedIdentity identity(UUID actor) {
        return new LegacyV1AuthenticatedIdentity(1, actor, UUID.randomUUID(), UUID.randomUUID(),
                NOW.plusSeconds(60), "owner", "Owner", false);
    }
    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame("{\"type\":\"CHANGE_NICKNAME_REQ\","
                + "\"data\":{\"displayName\":\"New Name\"}}");
    }
}
