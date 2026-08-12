package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendDirectorySnapshot;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendSummary;
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

final class V1FriendDirectoryHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
    private static final LegacyV1AuthenticatedIdentity IDENTITY =
            new LegacyV1AuthenticatedIdentity(
                    17,
                    UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    UUID.fromString("20000000-0000-0000-0000-000000000002"),
                    UUID.fromString("30000000-0000-0000-0000-000000000003"),
                    NOW.plusSeconds(3600), "alice", "Alice", false);

    @Test
    void usesServerBoundIdentityAndReturnsExactV1Fields() {
        var observed = new java.util.concurrent.atomic.AtomicReference<UUID>();
        EmbeddedChannel channel = channel(accountId -> {
            observed.set(accountId);
            return new LegacyV1FriendDirectorySnapshot(List.of(
                    new LegacyV1FriendSummary(
                            7, 8, "bob", "Bob", true, 2, 11)), 3);
        }, Runnable::run, V1FriendDirectoryEventSink.noop());
        try {
            authenticate(channel);
            assertFalse(channel.writeInbound(request()));
            channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            assertTrue(response.text().contains("\"type\":\"FRIEND_LIST_RSP\""));
            assertTrue(response.text().contains("\"friendshipId\":7"));
            assertTrue(response.text().contains("\"friendId\":8"));
            assertTrue(response.text().contains("\"peerLastReadMessageId\":11"));
            assertTrue(response.text().contains("\"pendingFriendRequests\":3"));
            response.release();
            assertEquals(IDENTITY.accountId(), observed.get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void forwardsOtherTrafficAndUnauthenticatedOwnedTraffic() {
        EmbeddedChannel channel = channel(
                ignored -> new LegacyV1FriendDirectorySnapshot(List.of(), 0),
                Runnable::run, V1FriendDirectoryEventSink.noop());
        try {
            assertTrue(channel.writeInbound(request()));
            ((TextWebSocketFrame) channel.readInbound()).release();
            authenticate(channel);
            TextWebSocketFrame room = new TextWebSocketFrame(
                    "{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}");
            assertTrue(channel.writeInbound(room));
            ((TextWebSocketFrame) channel.readInbound()).release();
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void closesWithoutPruningResponseOnFailureMalformedOrSaturation() {
        EmbeddedChannel failed = channel(ignored -> {
            throw new IllegalStateException("private mapping detail");
        }, Runnable::run, V1FriendDirectoryEventSink.noop());
        try {
            authenticate(failed);
            failed.writeInbound(request());
            failed.runPendingTasks();
            CloseWebSocketFrame close = failed.readOutbound();
            assertEquals("V1 friend directory unavailable", close.reasonText());
            close.release();
            assertFalse(failed.isActive());
        } finally {
            failed.finishAndReleaseAll();
        }

        EmbeddedChannel malformed = channel(
                ignored -> new LegacyV1FriendDirectorySnapshot(List.of(), 0),
                Runnable::run, V1FriendDirectoryEventSink.noop());
        try {
            authenticate(malformed);
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"FRIEND_LIST_REQ\",\"data\":[]}"));
            CloseWebSocketFrame close = malformed.readOutbound();
            close.release();
            assertFalse(malformed.isActive());
        } finally {
            malformed.finishAndReleaseAll();
        }

        AtomicInteger saturated = new AtomicInteger();
        V1FriendDirectoryEventSink events = new V1FriendDirectoryEventSink() {
            @Override public void completed(int friends, int pending, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { saturated.incrementAndGet(); }
        };
        EmbeddedChannel rejected = channel(
                ignored -> new LegacyV1FriendDirectorySnapshot(List.of(), 0),
                command -> { throw new RejectedExecutionException("full"); }, events);
        try {
            authenticate(rejected);
            rejected.writeInbound(request());
            ((CloseWebSocketFrame) rejected.readOutbound()).release();
            assertEquals(1, saturated.get());
            assertFalse(rejected.isActive());
        } finally {
            rejected.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel(
            com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendDirectoryUseCase use,
            java.util.concurrent.Executor executor,
            V1FriendDirectoryEventSink events) {
        return new EmbeddedChannel(new V1FriendDirectoryHandler(
                use,
                new V1JsonFriendDirectoryCodec(Clock.fixed(NOW, ZoneOffset.UTC)),
                executor,
                events));
    }

    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(IDENTITY);
    }

    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame(
                "{\"type\":\"FRIEND_LIST_REQ\",\"id\":\"one\",\"data\":{}}");
    }
}
