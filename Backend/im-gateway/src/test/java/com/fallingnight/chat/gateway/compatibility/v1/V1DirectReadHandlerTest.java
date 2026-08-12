package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectReadCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectReadResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectReadUseCase;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class V1DirectReadHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test void bindsActorRoutesNotificationWithoutSenderResponseAndPassesOtherFrames() {
        UUID actor = UUID.randomUUID(), target = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicReference<LegacyV1DirectReadCommand> captured = new AtomicReference<>();
        AtomicReference<V1DirectReadEventSink.Outcome> outcome = new AtomicReference<>();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel peer = new EmbeddedChannel(); registry.replace(target, peer);
        EmbeddedChannel reader = channel(actor, registry, command -> {
            captured.set(command);
            return new LegacyV1DirectReadResult.Marked(
                    conversation, 9, 2, 5, true, 101, target, "peer");
        }, Runnable::run, sink(outcome));
        try {
            reader.writeInbound(request()); reader.runPendingTasks(); peer.runPendingTasks();
            assertEquals(actor, captured.get().actorAccountId());
            assertEquals(9, captured.get().legacyFriendshipId());
            assertNull(reader.readOutbound());
            TextWebSocketFrame notification = peer.readOutbound();
            try {
                assertTrue(notification.text().contains("\"type\":\"FRIEND_READ_NOTIFY\""));
                assertTrue(notification.text().contains("\"friendshipId\":9"));
                assertTrue(notification.text().contains("\"readerUsername\":\"owner\""));
                assertTrue(notification.text().contains("\"lastReadMessageId\":101"));
            } finally { notification.release(); }
            assertEquals(V1DirectReadEventSink.Outcome.ADVANCED_ROUTE_SCHEDULED, outcome.get());
            TextWebSocketFrame other = new TextWebSocketFrame(
                    "{\"type\":\"FRIEND_LIST_REQ\",\"data\":{}}");
            assertTrue(reader.writeInbound(other));
            ((TextWebSocketFrame) reader.readInbound()).release();
        } finally { reader.finishAndReleaseAll(); peer.finishAndReleaseAll(); }
    }

    @Test void exactRepeatReroutesStableWatermarkAndSelfChatDoesNotRoute() {
        UUID actor = UUID.randomUUID(), target = UUID.randomUUID(), conversation = UUID.randomUUID();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel peer = new EmbeddedChannel(); registry.replace(target, peer);
        AtomicReference<V1DirectReadEventSink.Outcome> outcome = new AtomicReference<>();
        EmbeddedChannel reader = channel(actor, registry, command ->
                new LegacyV1DirectReadResult.Marked(
                        conversation, 9, 5, 5, false, 101, target, "peer"),
                Runnable::run, sink(outcome));
        try {
            reader.writeInbound(request()); reader.runPendingTasks(); peer.runPendingTasks();
            ((TextWebSocketFrame) peer.readOutbound()).release();
            assertEquals(V1DirectReadEventSink.Outcome.UNCHANGED_ROUTE_SCHEDULED, outcome.get());
        } finally { reader.finishAndReleaseAll(); peer.finishAndReleaseAll(); }

        AtomicReference<V1DirectReadEventSink.Outcome> selfOutcome = new AtomicReference<>();
        EmbeddedChannel self = channel(actor, new V1AccountConnectionRegistry(), command ->
                new LegacyV1DirectReadResult.Marked(
                        conversation, 9, 0, 0, false, 0, actor, "owner"),
                Runnable::run, sink(selfOutcome));
        try {
            self.writeInbound(request()); self.runPendingTasks();
            assertNull(self.readOutbound());
            assertEquals(V1DirectReadEventSink.Outcome.UNCHANGED_NO_LOCAL_ROUTE,
                    selfOutcome.get());
        } finally { self.finishAndReleaseAll(); }
    }

    @Test void denialIsSilentWhileMalformedAndSaturationClose() {
        UUID actor = UUID.randomUUID();
        EmbeddedChannel denied = channel(actor, new V1AccountConnectionRegistry(), command ->
                LegacyV1DirectReadResult.Rejected.FRIENDSHIP_ACCESS_DENIED,
                Runnable::run, V1DirectReadEventSink.noop());
        try {
            denied.writeInbound(request()); denied.runPendingTasks();
            assertNull(denied.readOutbound()); assertTrue(denied.isActive());
        } finally { denied.finishAndReleaseAll(); }

        EmbeddedChannel malformed = channel(actor, new V1AccountConnectionRegistry(),
                command -> { throw new AssertionError(); }, Runnable::run,
                V1DirectReadEventSink.noop());
        try {
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"MARK_FRIEND_READ\",\"data\":{\"friendshipId\":1.5}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel(actor, new V1AccountConnectionRegistry(), command ->
                LegacyV1DirectReadResult.Rejected.FRIENDSHIP_ACCESS_DENIED,
                command -> { throw new RejectedExecutionException(); },
                V1DirectReadEventSink.noop());
        try {
            saturated.writeInbound(request());
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(UUID actor, V1AccountConnectionRegistry registry,
            LegacyV1DirectReadUseCase reads, java.util.concurrent.Executor executor,
            V1DirectReadEventSink events) {
        EmbeddedChannel channel = new EmbeddedChannel(new V1DirectReadHandler(reads,
                new V1JsonDirectReadCodec(Clock.fixed(NOW, ZoneOffset.UTC)), registry,
                executor, events));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(
                new LegacyV1AuthenticatedIdentity(1, actor, UUID.randomUUID(), UUID.randomUUID(),
                        NOW.plusSeconds(60), "owner", "Owner", false));
        return channel;
    }

    private static V1DirectReadEventSink sink(
            AtomicReference<V1DirectReadEventSink.Outcome> outcome) {
        return new V1DirectReadEventSink() {
            @Override public void completed(Outcome result, long advancedBy, long nanos) {
                outcome.set(result);
            }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }

    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame(
                "{\"type\":\"MARK_FRIEND_READ\",\"data\":{\"friendshipId\":9}}");
    }
}
