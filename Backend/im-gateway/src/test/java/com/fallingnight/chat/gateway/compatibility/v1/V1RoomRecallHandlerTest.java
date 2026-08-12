package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

final class V1RoomRecallHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test void bindsActorAndRoutesOnlyFirstRecallToAuthorizedLocalMembers() {
        UUID actor = UUID.randomUUID(), peerId = UUID.randomUUID(), outsider = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel peer = new EmbeddedChannel(), outside = new EmbeddedChannel();
        registry.replace(peerId, peer); registry.replace(outsider, outside);
        EmbeddedChannel sender = new EmbeddedChannel(new V1RoomRecallHandler(command -> {
            assertEquals(actor, command.actorAccountId()); assertEquals(7, command.legacyRoomId());
            assertEquals(101, command.legacyMessageId());
            return new LegacyV1RoomRecallResult.Recalled(
                    false, conversation, 7, 101, 4, NOW);
        }, new LegacyV1RoomAudienceService((room, candidates) -> {
            assertEquals(conversation, room); return Set.of(peerId);
        }), new V1JsonRoomRecallCodec(Clock.fixed(NOW, ZoneOffset.UTC)), registry,
                Runnable::run, V1RoomRecallEventSink.noop()));
        try {
            sender.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
            registry.replace(actor, sender); sender.writeInbound(request()); sender.runPendingTasks();
            TextWebSocketFrame response = sender.readOutbound(), echo = sender.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"RECALL_RSP\""));
                assertTrue(response.text().contains("\"mutationSequence\":4"));
                assertTrue(echo.text().contains("\"type\":\"RECALL_NOTIFY\""));
                assertTrue(echo.text().contains("\"username\":\"owner\""));
            } finally { response.release(); echo.release(); }
            peer.runPendingTasks(); TextWebSocketFrame notification = peer.readOutbound();
            try { assertTrue(notification.text().contains("\"messageId\":101")); }
            finally { notification.release(); }
            outside.runPendingTasks(); assertNull(outside.readOutbound());
        } finally {
            sender.finishAndReleaseAll(); peer.finishAndReleaseAll(); outside.finishAndReleaseAll();
        }
    }

    @Test void duplicateSuppressesNotificationAndMalformedOrSaturatedCloses() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        EmbeddedChannel duplicate = channel(actor, command ->
                new LegacyV1RoomRecallResult.Recalled(true, conversation, 7, 101, 4, NOW),
                Runnable::run);
        try {
            duplicate.writeInbound(request()); duplicate.runPendingTasks();
            TextWebSocketFrame response = duplicate.readOutbound();
            try { assertTrue(response.text().contains("\"duplicate\":true")); }
            finally { response.release(); }
            assertNull(duplicate.readOutbound());
        } finally { duplicate.finishAndReleaseAll(); }

        EmbeddedChannel malformed = channel(actor, command -> { throw new AssertionError(); },
                Runnable::run);
        try {
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"RECALL_REQ\",\"data\":{\"roomId\":7,\"messageId\":1.5}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel(actor, command ->
                LegacyV1RoomRecallResult.Rejected.RECALL_REJECTED,
                command -> { throw new RejectedExecutionException(); });
        try {
            saturated.writeInbound(request());
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(UUID actor, LegacyV1RoomRecallUseCase recalls,
            java.util.concurrent.Executor executor) {
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel(new V1RoomRecallHandler(recalls,
                new LegacyV1RoomAudienceService((room, candidates) -> Set.of()),
                new V1JsonRoomRecallCodec(Clock.fixed(NOW, ZoneOffset.UTC)), registry,
                executor, V1RoomRecallEventSink.noop()));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
        registry.replace(actor, channel); return channel;
    }
    private static LegacyV1AuthenticatedIdentity identity(UUID actor) {
        return new LegacyV1AuthenticatedIdentity(1, actor, UUID.randomUUID(), UUID.randomUUID(),
                NOW.plusSeconds(60), "owner", "Owner", false);
    }
    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame(
                "{\"type\":\"RECALL_REQ\",\"data\":{\"roomId\":7,\"messageId\":101}} ");
    }
}
