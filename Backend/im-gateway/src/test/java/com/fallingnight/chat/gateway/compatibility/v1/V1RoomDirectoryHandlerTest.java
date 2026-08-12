package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomSummary;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class V1RoomDirectoryHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
    private static final LegacyV1AuthenticatedIdentity IDENTITY =
            new LegacyV1AuthenticatedIdentity(
                    17,
                    UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    UUID.fromString("20000000-0000-0000-0000-000000000002"),
                    UUID.fromString("30000000-0000-0000-0000-000000000003"),
                    NOW.plusSeconds(3600), "alice", "Alice", false);

    @Test
    void usesOnlyServerBoundIdentityAndReturnsACompleteRoomList() {
        var observed = new java.util.concurrent.atomic.AtomicReference<UUID>();
        EmbeddedChannel channel = channel(accountId -> {
            observed.set(accountId);
            return List.of(new LegacyV1RoomSummary(7, "Room", 2, true));
        }, Runnable::run, V1RoomDirectoryEventSink.noop());
        try {
            authenticate(channel);
            assertFalse(channel.writeInbound(request()));
            channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            assertTrue(response.text().contains("\"type\":\"ROOM_LIST_RSP\""));
            assertTrue(response.text().contains("\"roomId\":7"));
            response.release();
            assertEquals(IDENTITY.accountId(), observed.get());
            assertTrue(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void forwardsUnownedTrafficAndDoesNotServeAnUnauthenticatedRequest() {
        EmbeddedChannel channel = channel(accountId -> List.of(), Runnable::run,
                V1RoomDirectoryEventSink.noop());
        try {
            assertTrue(channel.writeInbound(request()));
            ((TextWebSocketFrame) channel.readInbound()).release();
            authenticate(channel);
            TextWebSocketFrame friend = new TextWebSocketFrame(
                    "{\"type\":\"FRIEND_LIST_REQ\",\"data\":{}}");
            assertTrue(channel.writeInbound(friend));
            ((TextWebSocketFrame) channel.readInbound()).release();
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void closesWithoutAnEmptyListOnMappingFailureOrMalformedOwnedInput() {
        EmbeddedChannel failed = channel(accountId -> {
            throw new IllegalStateException("mapping details must not escape");
        }, Runnable::run, V1RoomDirectoryEventSink.noop());
        try {
            authenticate(failed);
            assertFalse(failed.writeInbound(request()));
            failed.runPendingTasks();
            CloseWebSocketFrame close = failed.readOutbound();
            assertEquals("V1 room directory unavailable", close.reasonText());
            close.release();
            assertFalse(failed.isActive());
            assertNull(failed.readOutbound());
        } finally {
            failed.finishAndReleaseAll();
        }

        EmbeddedChannel malformed = channel(accountId -> List.of(), Runnable::run,
                V1RoomDirectoryEventSink.noop());
        try {
            authenticate(malformed);
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"ROOM_LIST_REQ\",\"data\":[]}"));
            CloseWebSocketFrame close = malformed.readOutbound();
            assertEquals("V1 room directory unavailable", close.reasonText());
            close.release();
            assertFalse(malformed.isActive());
        } finally {
            malformed.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsSaturationAndConcurrentRequestsWithFixedTelemetry() {
        AtomicInteger saturated = new AtomicInteger();
        V1RoomDirectoryEventSink events = new V1RoomDirectoryEventSink() {
            @Override public void completed(int roomCount, long executionNanos) { }
            @Override public void failed() { }
            @Override public void saturated() { saturated.incrementAndGet(); }
        };
        EmbeddedChannel rejected = channel(accountId -> List.of(), command -> {
            throw new RejectedExecutionException("full");
        }, events);
        try {
            authenticate(rejected);
            rejected.writeInbound(request());
            CloseWebSocketFrame close = rejected.readOutbound();
            close.release();
            assertEquals(1, saturated.get());
            assertFalse(rejected.isActive());
        } finally {
            rejected.finishAndReleaseAll();
        }

        var queued = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        EmbeddedChannel concurrent = channel(accountId -> List.of(), queued::set,
                V1RoomDirectoryEventSink.noop());
        try {
            authenticate(concurrent);
            concurrent.writeInbound(request());
            concurrent.writeInbound(request());
            CloseWebSocketFrame close = concurrent.readOutbound();
            close.release();
            assertFalse(concurrent.isActive());
        } finally {
            concurrent.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel(
            com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomDirectoryUseCase useCase,
            java.util.concurrent.Executor executor,
            V1RoomDirectoryEventSink events) {
        return new EmbeddedChannel(new V1RoomDirectoryHandler(
                useCase,
                new V1JsonRoomDirectoryCodec(Clock.fixed(NOW, ZoneOffset.UTC)),
                executor,
                events));
    }

    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(IDENTITY);
    }

    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame(
                "{\"type\":\"ROOM_LIST_REQ\",\"id\":\"one\",\"data\":{}}");
    }
}
