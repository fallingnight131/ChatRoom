package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

final class V1RoomLeaveHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();

    @Test void codecAcceptsOnlyIntegralRoomIdAndKnownDataFields() {
        var codec = codec();
        assertEquals(V1JsonRoomLeaveCodec.RequestKind.LEAVE, codec.decode(
                "{\"type\":\"LEAVE_ROOM\",\"data\":{\"roomId\":7}}"
                        .getBytes(StandardCharsets.UTF_8)).kind());
        assertEquals(V1JsonRoomLeaveCodec.RequestKind.MALFORMED_LEAVE, codec.decode(
                "{\"type\":\"LEAVE_ROOM\",\"data\":{\"roomId\":7,\"role\":\"OWNER\"}}"
                        .getBytes(StandardCharsets.UTF_8)).kind());
        assertEquals(V1JsonRoomLeaveCodec.RequestKind.MALFORMED_LEAVE, codec.decode(
                "{\"type\":\"LEAVE_ROOM\",\"data\":{\"roomId\":1.5}}"
                        .getBytes(StandardCharsets.UTF_8)).kind());
    }

    @Test void bindsActorAndRoutesFirstLeaveWithOwnershipTransfer() {
        UUID conversation = UUID.randomUUID(), successor = UUID.randomUUID();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel successorChannel = new EmbeddedChannel();
        registry.replace(successor, successorChannel);
        AtomicReference<UUID> actualActor = new AtomicReference<>();
        EmbeddedChannel leaving = channel((actor, room) -> {
            actualActor.set(actor);
            return new LegacyV1RoomLeaveResult.Left(conversation, room, actor,
                    true, false, Optional.of(
                            new LegacyV1RoomLeaveResult.OwnershipTransfer(
                                    successor, "Next Owner")));
        }, new LegacyV1RoomAudienceService((id, candidates) -> {
            assertEquals(conversation, id); assertTrue(candidates.contains(successor));
            return Set.of(successor);
        }), registry, Runnable::run);
        authenticate(leaving);
        try {
            leaving.writeInbound(request(7)); leaving.runPendingTasks();
            TextWebSocketFrame response = leaving.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"LEAVE_ROOM_RSP\""));
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"roomId\":7"));
                assertFalse(response.text().contains("conversationId"));
            } finally { response.release(); }
            successorChannel.runPendingTasks();
            assertFrame(successorChannel, "USER_LEFT");
            assertFrame(successorChannel, "ADMIN_STATUS");
            assertFrame(successorChannel, "SYSTEM_MSG");
            assertEquals(ACTOR, actualActor.get());
        } finally {
            leaving.finishAndReleaseAll(); successorChannel.finishAndReleaseAll();
        }
    }

    @Test void duplicateDissolutionAndRejectionNeverProjectAudience() {
        UUID conversation = UUID.randomUUID(); AtomicBoolean called = new AtomicBoolean();
        var audience = new LegacyV1RoomAudienceService((id, candidates) -> {
            called.set(true); return Set.of();
        });
        for (LegacyV1RoomLeaveResult result : new LegacyV1RoomLeaveResult[] {
                new LegacyV1RoomLeaveResult.Left(
                        conversation, 7, ACTOR, false, false, Optional.empty()),
                new LegacyV1RoomLeaveResult.Left(
                        conversation, 7, ACTOR, true, true, Optional.empty()),
                LegacyV1RoomLeaveResult.Rejected.NOT_MEMBER}) {
            EmbeddedChannel channel = channel((actor, room) -> result, audience,
                    new V1AccountConnectionRegistry(), Runnable::run);
            authenticate(channel);
            try {
                channel.writeInbound(request(7)); channel.runPendingTasks();
                TextWebSocketFrame response = channel.readOutbound();
                try { assertTrue(response.text().contains("LEAVE_ROOM_RSP")); }
                finally { response.release(); }
                assertTrue(channel.isActive());
            } finally { channel.finishAndReleaseAll(); }
        }
        assertFalse(called.get());
    }

    @Test void committedLeaveSurvivesAudienceFailure() {
        UUID conversation = UUID.randomUUID();
        EmbeddedChannel channel = channel((actor, room) ->
                new LegacyV1RoomLeaveResult.Left(
                        conversation, room, actor, true, false, Optional.empty()),
                new LegacyV1RoomAudienceService((id, candidates) -> {
                    throw new IllegalStateException("projection unavailable after commit");
                }), new V1AccountConnectionRegistry(), Runnable::run);
        authenticate(channel);
        try {
            channel.writeInbound(request(7)); channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            try { assertTrue(response.text().contains("\"success\":true")); }
            finally { response.release(); }
            assertTrue(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void malformedDependencyFailureAndSaturationClose() {
        EmbeddedChannel malformed = channel((actor, room) -> { throw new AssertionError(); },
                emptyAudience(), new V1AccountConnectionRegistry(), Runnable::run);
        authenticate(malformed);
        try {
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"LEAVE_ROOM\",\"data\":{\"roomId\":1.5}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel failed = channel((actor, room) -> {
            throw new IllegalStateException("private");
        }, emptyAudience(), new V1AccountConnectionRegistry(), Runnable::run);
        authenticate(failed);
        try {
            failed.writeInbound(request(7)); failed.runPendingTasks();
            ((CloseWebSocketFrame) failed.readOutbound()).release();
            assertFalse(failed.isActive());
        } finally { failed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel((actor, room) ->
                LegacyV1RoomLeaveResult.Rejected.NOT_MEMBER, emptyAudience(),
                new V1AccountConnectionRegistry(), task -> {
                    throw new RejectedExecutionException("full");
                });
        authenticate(saturated);
        try {
            saturated.writeInbound(request(7));
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(LegacyV1RoomLeaveUseCase useCase,
            LegacyV1RoomAudienceService audience, V1AccountConnectionRegistry registry,
            java.util.concurrent.Executor executor) {
        return new EmbeddedChannel(new V1RoomLeaveHandler(useCase, audience, codec(),
                registry, executor, V1RoomLeaveEventSink.noop()));
    }
    private static LegacyV1RoomAudienceService emptyAudience() {
        return new LegacyV1RoomAudienceService((id, candidates) -> Set.of());
    }
    private static V1JsonRoomLeaveCodec codec() {
        return new V1JsonRoomLeaveCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static TextWebSocketFrame request(long roomId) {
        return new TextWebSocketFrame("{\"type\":\"LEAVE_ROOM\",\"data\":{\"roomId\":"
                + roomId + "}}");
    }
    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(
                new LegacyV1AuthenticatedIdentity(42, ACTOR, UUID.randomUUID(), UUID.randomUUID(),
                        NOW.plusSeconds(60), "owner", "Owner", false));
    }
    private static void assertFrame(EmbeddedChannel channel, String type) {
        TextWebSocketFrame frame = channel.readOutbound();
        assertNotNull(frame);
        try { assertTrue(frame.text().contains("\"type\":\"" + type + "\"")); }
        finally { frame.release(); }
    }
}
