package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1LoginResult;
import com.fallingnight.chat.gateway.transport.AuthenticationAdmissionControl;
import com.fallingnight.chat.gateway.transport.AuthenticationAdmissionDecision;
import com.fallingnight.chat.gateway.transport.AuthenticationEventSink;
import com.fallingnight.chat.gateway.transport.AuthenticationLimitDimension;
import com.fallingnight.chat.gateway.transport.AuthenticationOutcome;
import com.fallingnight.chat.gateway.transport.V2ConnectionAttributes;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class V1WebLoginHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    @Test
    void authenticatesOffLoopBindsIdentityAndForwardsLaterFrames() {
        Queue<Runnable> work = new ArrayDeque<>();
        RecordingAdmission admission = new RecordingAdmission();
        RecordingEvents events = new RecordingEvents();
        LegacyV1AuthenticatedIdentity identity = identity(true);
        EmbeddedChannel channel = channel(
                command -> {
                    assertEquals("alice", command.username());
                    return new LegacyV1LoginResult.Established(identity);
                },
                work::add,
                admission,
                events);
        channel.attr(V2ConnectionAttributes.CLIENT_PEER_ADDRESS).set("198.51.100.7");
        try {
            assertFalse(channel.writeInbound(validFrame()));
            assertEquals(1, work.size());
            assertEquals("198.51.100.7", admission.peer);
            assertEquals("alice", admission.username);

            work.remove().run();
            channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"userId\":17"));
            } finally {
                response.release();
            }
            assertSame(identity, channel.attr(V1ConnectionAttributes.AUTHENTICATED).get());
            assertEquals("alice", admission.successfulUsername);
            assertEquals(1, events.accepted);
            assertTrue(events.upgradePending);
            assertEquals(AuthenticationOutcome.ACCEPTED, events.completedOutcome);

            TextWebSocketFrame next = new TextWebSocketFrame("{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}");
            assertTrue(channel.writeInbound(next));
            TextWebSocketFrame forwarded = channel.readInbound();
            assertEquals("{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}", forwarded.text());
            forwarded.release();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void malformedDeniedAndSaturatedRequestsUseTheSameResponseAndClose() {
        AtomicBoolean invoked = new AtomicBoolean();
        EmbeddedChannel malformed = channel(
                command -> {
                    invoked.set(true);
                    return LegacyV1LoginResult.Rejected.INSTANCE;
                },
                Runnable::run,
                new RecordingAdmission(),
                new RecordingEvents());
        assertGenericClose(malformed, new TextWebSocketFrame("not-json"));
        assertFalse(invoked.get());

        RecordingAdmission deniedAdmission = new RecordingAdmission();
        deniedAdmission.decision = AuthenticationAdmissionDecision.deny(
                AuthenticationLimitDimension.ACCOUNT, 1000);
        RecordingEvents deniedEvents = new RecordingEvents();
        EmbeddedChannel denied = channel(
                command -> {
                    invoked.set(true);
                    return LegacyV1LoginResult.Rejected.INSTANCE;
                },
                Runnable::run,
                deniedAdmission,
                deniedEvents);
        assertGenericClose(denied, validFrame());
        assertEquals(AuthenticationLimitDimension.ACCOUNT, deniedEvents.deniedDimension);

        RecordingEvents saturatedEvents = new RecordingEvents();
        EmbeddedChannel saturated = channel(
                command -> {
                    invoked.set(true);
                    return LegacyV1LoginResult.Rejected.INSTANCE;
                },
                command -> {
                    throw new RejectedExecutionException("full");
                },
                new RecordingAdmission(),
                saturatedEvents);
        assertGenericClose(saturated, validFrame());
        assertEquals(1, saturatedEvents.saturated);
        assertFalse(invoked.get());
    }

    @Test
    void rejectedAndUnexpectedAuthenticationShareGenericWireFailure() {
        RecordingEvents rejectedEvents = new RecordingEvents();
        EmbeddedChannel rejected = channel(
                command -> LegacyV1LoginResult.Rejected.INSTANCE,
                Runnable::run,
                new RecordingAdmission(),
                rejectedEvents);
        assertGenericClose(rejected, validFrame());
        assertEquals(1, rejectedEvents.rejected);
        assertEquals(AuthenticationOutcome.REJECTED, rejectedEvents.completedOutcome);

        RecordingEvents failedEvents = new RecordingEvents();
        EmbeddedChannel failed = channel(
                command -> {
                    throw new IllegalStateException("database endpoint must not leak");
                },
                Runnable::run,
                new RecordingAdmission(),
                failedEvents);
        assertGenericClose(failed, validFrame());
        assertEquals(1, failedEvents.failed);
        assertEquals(AuthenticationOutcome.FAILED, failedEvents.completedOutcome);
    }

    @Test
    void concurrentSecondAttemptClosesWithoutLateSuccess() {
        Queue<Runnable> work = new ArrayDeque<>();
        EmbeddedChannel channel = channel(
                command -> new LegacyV1LoginResult.Established(identity(false)),
                work::add,
                new RecordingAdmission(),
                new RecordingEvents());
        try {
            channel.writeInbound(validFrame());
            assertEquals(1, work.size());
            channel.writeInbound(validFrame());
            TextWebSocketFrame rejection = channel.readOutbound();
            assertNotNull(rejection);
            rejection.release();
            assertFalse(channel.isActive());

            work.remove().run();
            channel.runPendingTasks();
            assertNull(channel.readOutbound());
            assertNull(channel.attr(V1ConnectionAttributes.AUTHENTICATED).get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel(
            com.fallingnight.chat.application.compatibility.v1.LegacyV1LoginUseCase login,
            java.util.concurrent.Executor executor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events) {
        return new EmbeddedChannel(new V1WebLoginHandler(
                login,
                new V1JsonLoginCodec(Clock.fixed(NOW, ZoneOffset.UTC)),
                executor,
                admission,
                events));
    }

    private static TextWebSocketFrame validFrame() {
        return new TextWebSocketFrame(
                "{\"type\":\"LOGIN_REQ\",\"id\":\"request-1\",\"timestamp\":1,"
                        + "\"data\":{\"username\":\"alice\",\"password\":\"secret\"}}");
    }

    private static LegacyV1AuthenticatedIdentity identity(boolean upgradePending) {
        return new LegacyV1AuthenticatedIdentity(
                17,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                NOW.plusSeconds(3600),
                "alice",
                "Alice",
                upgradePending);
    }

    private static void assertGenericClose(
            EmbeddedChannel channel, TextWebSocketFrame request) {
        try {
            channel.writeInbound(request);
            channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            assertNotNull(response);
            try {
                assertTrue(response.text().contains("\"success\":false"));
                assertTrue(response.text().contains("用户ID或密码错误"));
                assertFalse(response.text().contains("database"));
            } finally {
                response.release();
            }
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static final class RecordingAdmission implements AuthenticationAdmissionControl {
        private AuthenticationAdmissionDecision decision =
                AuthenticationAdmissionDecision.allow();
        private String peer;
        private String username;
        private String successfulUsername;

        @Override
        public AuthenticationAdmissionDecision acquire(
                String directPeer, String presentedUsername) {
            peer = directPeer;
            username = presentedUsername;
            return decision;
        }

        @Override
        public AuthenticationAdmissionDecision acquireResume(String directPeer) {
            return AuthenticationAdmissionDecision.allow();
        }

        @Override
        public void recordSuccess(String presentedUsername) {
            successfulUsername = presentedUsername;
        }
    }

    private static final class RecordingEvents implements AuthenticationEventSink {
        private int accepted;
        private int rejected;
        private int failed;
        private int saturated;
        private boolean upgradePending;
        private AuthenticationLimitDimension deniedDimension;
        private AuthenticationOutcome completedOutcome;

        @Override
        public void accepted(boolean credentialUpgradePending) {
            accepted++;
            upgradePending = credentialUpgradePending;
        }

        @Override
        public void rejected() {
            rejected++;
        }

        @Override
        public void failed() {
            failed++;
        }

        @Override
        public void saturated() {
            saturated++;
        }

        @Override
        public void admissionDenied(AuthenticationLimitDimension dimension) {
            deniedDimension = dimension;
        }

        @Override
        public void completed(
                AuthenticationOutcome outcome,
                boolean credentialUpgradePending,
                long executionNanos) {
            completedOutcome = outcome;
        }
    }
}
