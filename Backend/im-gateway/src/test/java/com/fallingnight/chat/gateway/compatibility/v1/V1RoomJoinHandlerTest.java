package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.compatibility.v1.*;
import com.fallingnight.chat.gateway.transport.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

final class V1RoomJoinHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();

    @Test void codecOwnsPasswordAndRejectsUnknownDataFields() {
        V1JsonRoomJoinCodec codec = codec();
        var decoded = codec.decode(("{\"type\":\"JOIN_ROOM_REQ\",\"data\":{"
                + "\"roomId\":7,\"password\":\"密码secret\"}}")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(V1JsonRoomJoinCodec.RequestKind.JOIN, decoded.kind());
        assertEquals(7, decoded.roomId());
        assertEquals("密码secret", new String(decoded.passwordCopy(), StandardCharsets.UTF_8));
        decoded.close(); assertNull(decoded.passwordCopy());

        var malformed = codec.decode(("{\"type\":\"JOIN_ROOM_REQ\",\"data\":"
                + "{\"roomId\":7,\"role\":\"OWNER\"}}")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(V1JsonRoomJoinCodec.RequestKind.MALFORMED_JOIN, malformed.kind());
    }

    @Test void bindsActorLimitsPasswordAndRoutesOnlyCommittedFirstJoin() {
        UUID conversation = UUID.randomUUID(), member = UUID.randomUUID(), outsider = UUID.randomUUID();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel memberChannel = new EmbeddedChannel(), outsiderChannel = new EmbeddedChannel();
        registry.replace(member, memberChannel); registry.replace(outsider, outsiderChannel);
        RecordingAdmission admission = new RecordingAdmission(AuthenticationAdmissionDecision.allow());
        AtomicReference<UUID> actor = new AtomicReference<>();
        EmbeddedChannel joining = channel(command -> {
            actor.set(command.actorAccountId());
            return new LegacyV1RoomJoinResult.Joined(conversation, 7, "Room",
                    command.actorAccountId(), LegacyV1RoomJoinResult.Role.MEMBER, true);
        }, new LegacyV1RoomAudienceService((actual, candidates) -> {
            assertEquals(conversation, actual); assertTrue(candidates.contains(outsider));
            return Set.of(member);
        }), registry, admission, Runnable::run);
        authenticate(joining);
        try {
            joining.writeInbound(request(7, "secret")); joining.runPendingTasks();
            TextWebSocketFrame response = joining.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"JOIN_ROOM_RSP\""));
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"roomId\":7"));
                assertTrue(response.text().contains("\"newJoin\":true"));
                assertTrue(response.text().contains("\"isAdmin\":false"));
                assertFalse(response.text().contains("conversationId"));
            } finally { response.release(); }
            memberChannel.runPendingTasks(); TextWebSocketFrame notification = memberChannel.readOutbound();
            try {
                assertTrue(notification.text().contains("\"type\":\"USER_JOINED\""));
                assertTrue(notification.text().contains("\"username\":\"owner\""));
            } finally { notification.release(); }
            outsiderChannel.runPendingTasks(); assertNull(outsiderChannel.readOutbound());
            assertEquals(ACTOR, actor.get()); assertEquals("room:7", admission.presented.get());
            assertEquals("room:7", admission.success.get());
        } finally {
            joining.finishAndReleaseAll(); memberChannel.finishAndReleaseAll();
            outsiderChannel.finishAndReleaseAll();
        }
    }

    @Test void preservesPasswordChallengesAndDuplicateDoesNotNotify() {
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel missing = channel(command ->
                LegacyV1RoomJoinResult.Rejected.PASSWORD_REQUIRED, emptyAudience(), registry,
                new RecordingAdmission(AuthenticationAdmissionDecision.allow()), Runnable::run);
        authenticate(missing);
        try {
            missing.writeInbound(request(9, null)); missing.runPendingTasks();
            TextWebSocketFrame response = missing.readOutbound();
            try {
                assertTrue(response.text().contains("\"needPassword\":true"));
                assertTrue(response.text().contains("\"roomId\":9"));
            } finally { response.release(); }
            assertTrue(missing.isActive());
        } finally { missing.finishAndReleaseAll(); }

        AtomicBoolean audienceCalled = new AtomicBoolean();
        UUID conversation = UUID.randomUUID();
        EmbeddedChannel duplicate = channel(command -> new LegacyV1RoomJoinResult.Joined(
                conversation, 9, "Room", ACTOR, LegacyV1RoomJoinResult.Role.ADMIN, false),
                new LegacyV1RoomAudienceService((id, candidates) -> {
                    audienceCalled.set(true); return Set.of();
                }), registry, new RecordingAdmission(AuthenticationAdmissionDecision.allow()),
                Runnable::run);
        authenticate(duplicate);
        try {
            duplicate.writeInbound(request(9, null)); duplicate.runPendingTasks();
            TextWebSocketFrame response = duplicate.readOutbound();
            try {
                assertTrue(response.text().contains("\"newJoin\":false"));
                assertTrue(response.text().contains("\"isAdmin\":true"));
            } finally { response.release(); }
            assertFalse(audienceCalled.get());
        } finally { duplicate.finishAndReleaseAll(); }
    }

    @Test void admissionDenialReturnsRetryWithoutHashWorkOrClosing() {
        AtomicBoolean called = new AtomicBoolean();
        RecordingAdmission admission = new RecordingAdmission(AuthenticationAdmissionDecision.deny(
                AuthenticationLimitDimension.ACCOUNT, 1234));
        EmbeddedChannel channel = channel(command -> {
            called.set(true); return LegacyV1RoomJoinResult.Rejected.INVALID_PASSWORD;
        }, emptyAudience(), new V1AccountConnectionRegistry(), admission, Runnable::run);
        authenticate(channel);
        try {
            channel.writeInbound(request(7, "wrong"));
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"errorCode\":\"RATE_LIMITED\""));
                assertTrue(response.text().contains("\"retryAfterMs\":1234"));
            } finally { response.release(); }
            assertFalse(called.get()); assertTrue(channel.isActive()); assertNull(admission.success.get());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void wrongPasswordDoesNotResetLimiterAndCommittedJoinSurvivesRoutingFailure() {
        RecordingAdmission wrongAdmission = new RecordingAdmission(
                AuthenticationAdmissionDecision.allow());
        EmbeddedChannel wrong = channel(command ->
                LegacyV1RoomJoinResult.Rejected.INVALID_PASSWORD, emptyAudience(),
                new V1AccountConnectionRegistry(), wrongAdmission, Runnable::run);
        authenticate(wrong);
        try {
            wrong.writeInbound(request(7, "wrong")); wrong.runPendingTasks();
            TextWebSocketFrame response = wrong.readOutbound();
            try { assertTrue(response.text().contains("\"errorCode\":\"INVALID_PASSWORD\"")); }
            finally { response.release(); }
            assertNull(wrongAdmission.success.get()); assertTrue(wrong.isActive());
        } finally { wrong.finishAndReleaseAll(); }

        UUID conversation = UUID.randomUUID();
        EmbeddedChannel committed = channel(command -> new LegacyV1RoomJoinResult.Joined(
                conversation, 7, "Room", ACTOR, LegacyV1RoomJoinResult.Role.MEMBER, true),
                new LegacyV1RoomAudienceService((id, candidates) -> {
                    throw new IllegalStateException("database unavailable after commit");
                }), new V1AccountConnectionRegistry(),
                new RecordingAdmission(AuthenticationAdmissionDecision.allow()), Runnable::run);
        authenticate(committed);
        try {
            committed.writeInbound(request(7, null)); committed.runPendingTasks();
            TextWebSocketFrame response = committed.readOutbound();
            try { assertTrue(response.text().contains("\"success\":true")); }
            finally { response.release(); }
            assertTrue(committed.isActive());
        } finally { committed.finishAndReleaseAll(); }
    }

    @Test void malformedDependencyFailureAndSaturationClose() {
        EmbeddedChannel malformed = channel(command -> { throw new AssertionError(); },
                emptyAudience(), new V1AccountConnectionRegistry(),
                new RecordingAdmission(AuthenticationAdmissionDecision.allow()), Runnable::run);
        authenticate(malformed);
        try {
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"JOIN_ROOM_REQ\",\"data\":{\"roomId\":1.5}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel failed = channel(command -> { throw new IllegalStateException("private"); },
                emptyAudience(), new V1AccountConnectionRegistry(),
                new RecordingAdmission(AuthenticationAdmissionDecision.allow()), Runnable::run);
        authenticate(failed);
        try {
            failed.writeInbound(request(7, null)); failed.runPendingTasks();
            ((CloseWebSocketFrame) failed.readOutbound()).release(); assertFalse(failed.isActive());
        } finally { failed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel(command -> LegacyV1RoomJoinResult.Rejected.JOIN_DENIED,
                emptyAudience(), new V1AccountConnectionRegistry(),
                new RecordingAdmission(AuthenticationAdmissionDecision.allow()), task -> {
                    throw new RejectedExecutionException("full");
                });
        authenticate(saturated);
        try {
            saturated.writeInbound(request(7, null));
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(LegacyV1RoomJoinUseCase useCase,
            LegacyV1RoomAudienceService audience, V1AccountConnectionRegistry registry,
            AuthenticationAdmissionControl admission, java.util.concurrent.Executor executor) {
        return new EmbeddedChannel(new V1RoomJoinHandler(useCase, audience, codec(), registry,
                executor, admission, V1RoomJoinEventSink.noop()));
    }
    private static LegacyV1RoomAudienceService emptyAudience() {
        return new LegacyV1RoomAudienceService((id, candidates) -> Set.of());
    }
    private static V1JsonRoomJoinCodec codec() {
        return new V1JsonRoomJoinCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(
                new LegacyV1AuthenticatedIdentity(42, ACTOR, UUID.randomUUID(), UUID.randomUUID(),
                        NOW.plusSeconds(60), "owner", "Owner", false));
    }
    private static TextWebSocketFrame request(long roomId, String password) {
        return new TextWebSocketFrame("{\"type\":\"JOIN_ROOM_REQ\",\"id\":\"join\",\"data\":{"
                + "\"roomId\":" + roomId
                + (password == null ? "" : ",\"password\":\"" + password + "\"") + "}}");
    }

    private static final class RecordingAdmission implements AuthenticationAdmissionControl {
        private final AuthenticationAdmissionDecision decision;
        private final AtomicReference<String> presented = new AtomicReference<>();
        private final AtomicReference<String> success = new AtomicReference<>();
        private RecordingAdmission(AuthenticationAdmissionDecision decision) {
            this.decision = decision;
        }
        @Override public AuthenticationAdmissionDecision acquire(String peer, String name) {
            assertNotNull(peer); presented.set(name); return decision;
        }
        @Override public AuthenticationAdmissionDecision acquireResume(String peer) {
            throw new AssertionError();
        }
        @Override public void recordSuccess(String name) { success.set(name); }
    }
}
