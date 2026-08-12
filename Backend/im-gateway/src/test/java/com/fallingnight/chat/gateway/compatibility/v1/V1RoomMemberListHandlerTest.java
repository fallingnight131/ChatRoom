package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class V1RoomMemberListHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();

    @Test void bindsActorAndEncodesCompatibleUuidFreeMembers() {
        AtomicReference<UUID> observed = new AtomicReference<>();
        EmbeddedChannel channel = channel((actor, room) -> {
            observed.set(actor); assertEquals(7, room);
            return new LegacyV1RoomMemberListResult.Listed(7, List.of(
                    new LegacyV1RoomMemberUser("owner", "Owner", true, true),
                    new LegacyV1RoomMemberUser("peer", "Peer", false, false)));
        }, Runnable::run);
        authenticate(channel);
        try {
            channel.writeInbound(request()); channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"USER_LIST_RSP\""));
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"username\":\"owner\""));
                assertTrue(response.text().contains("\"isAdmin\":true"));
                assertTrue(response.text().contains("\"isOnline\":false"));
                assertFalse(response.text().contains(ACTOR.toString()));
            } finally { response.release(); }
            assertEquals(ACTOR, observed.get()); assertTrue(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void stableRejectionKeepsConnectionUsable() {
        EmbeddedChannel channel = channel((actor, room) ->
                LegacyV1RoomMemberListResult.Rejected.ROOM_ACCESS_DENIED, Runnable::run);
        authenticate(channel);
        try {
            channel.writeInbound(request()); channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"success\":false"));
                assertTrue(response.text().contains(
                        "\"errorCode\":\"ROOM_ACCESS_DENIED\""));
                assertTrue(response.text().contains("\"roomId\":7"));
            } finally { response.release(); }
            assertTrue(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void malformedFailureSaturationAndConcurrentClose() {
        EmbeddedChannel malformed = channel((actor, room) -> { throw new AssertionError(); },
                Runnable::run); authenticate(malformed);
        try {
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"USER_LIST_REQ\",\"data\":{\"roomId\":7,\"role\":1}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel failed = channel((actor, room) -> {
            throw new IllegalStateException("private");
        }, Runnable::run); authenticate(failed);
        try {
            failed.writeInbound(request()); failed.runPendingTasks();
            ((CloseWebSocketFrame) failed.readOutbound()).release();
            assertFalse(failed.isActive());
        } finally { failed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel((actor, room) ->
                LegacyV1RoomMemberListResult.Rejected.ROOM_ACCESS_DENIED, task -> {
                    throw new RejectedExecutionException("full");
                }); authenticate(saturated);
        try {
            saturated.writeInbound(request());
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }

        AtomicReference<Runnable> queued = new AtomicReference<>();
        EmbeddedChannel concurrent = channel((actor, room) ->
                LegacyV1RoomMemberListResult.Rejected.ROOM_ACCESS_DENIED, queued::set);
        authenticate(concurrent);
        try {
            concurrent.writeInbound(request()); concurrent.writeInbound(request());
            ((CloseWebSocketFrame) concurrent.readOutbound()).release();
            assertFalse(concurrent.isActive());
        } finally { concurrent.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(LegacyV1RoomMemberListUseCase useCase,
            java.util.concurrent.Executor executor) {
        return new EmbeddedChannel(new V1RoomMemberListHandler(useCase,
                new V1JsonRoomMemberListCodec(Clock.fixed(NOW, ZoneOffset.UTC)), executor,
                V1RoomMemberListEventSink.noop()));
    }
    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(
                new LegacyV1AuthenticatedIdentity(42, ACTOR, UUID.randomUUID(), UUID.randomUUID(),
                        NOW.plusSeconds(60), "owner", "Owner", false));
    }
    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame(
                "{\"type\":\"USER_LIST_REQ\",\"data\":{\"roomId\":7}}");
    }
}
