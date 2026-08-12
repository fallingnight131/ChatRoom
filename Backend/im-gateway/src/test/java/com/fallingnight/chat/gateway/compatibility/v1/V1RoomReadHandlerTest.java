package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class V1RoomReadHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    @Test void bindsActorAdvancesWithoutResponseAndPassesOtherFrames() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicReference<LegacyV1RoomReadCommand> captured = new AtomicReference<>();
        EmbeddedChannel channel = channel(actor, command -> {
            captured.set(command); return new LegacyV1RoomReadResult.Marked(
                    conversation, 7, 2, 8, true);
        }, Runnable::run);
        try {
            channel.writeInbound(request()); channel.runPendingTasks();
            assertEquals(actor, captured.get().actorAccountId());
            assertEquals(7, captured.get().legacyRoomId()); assertNull(channel.readOutbound());
            TextWebSocketFrame other = new TextWebSocketFrame(
                    "{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}");
            assertTrue(channel.writeInbound(other)); TextWebSocketFrame passed = channel.readInbound();
            passed.release(); assertTrue(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void businessDenialHasNoResponseAndMalformedOrSaturatedCloses() {
        UUID actor = UUID.randomUUID();
        EmbeddedChannel denied = channel(actor, command ->
                LegacyV1RoomReadResult.Rejected.ROOM_ACCESS_DENIED, Runnable::run);
        try {
            denied.writeInbound(request()); denied.runPendingTasks();
            assertNull(denied.readOutbound()); assertTrue(denied.isActive());
        } finally { denied.finishAndReleaseAll(); }
        EmbeddedChannel malformed = channel(actor, command -> { throw new AssertionError(); }, Runnable::run);
        try {
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"MARK_ROOM_READ\",\"data\":{\"roomId\":1.5}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release(); assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }
        EmbeddedChannel saturated = channel(actor, command ->
                LegacyV1RoomReadResult.Rejected.ROOM_ACCESS_DENIED,
                command -> { throw new RejectedExecutionException(); });
        try {
            saturated.writeInbound(request());
            ((CloseWebSocketFrame) saturated.readOutbound()).release(); assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(UUID actor, LegacyV1RoomReadUseCase reads,
            java.util.concurrent.Executor executor) {
        EmbeddedChannel channel = new EmbeddedChannel(new V1RoomReadHandler(
                reads, new V1JsonRoomReadCodec(), executor, V1RoomReadEventSink.noop()));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(new LegacyV1AuthenticatedIdentity(
                1, actor, UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(60),
                "owner", "Owner", false));
        return channel;
    }
    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame(
                "{\"type\":\"MARK_ROOM_READ\",\"data\":{\"roomId\":7}} ");
    }
}
