package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.identity.AuthenticationResult;
import com.fallingnight.chat.application.identity.AuthenticationUseCase;
import com.fallingnight.chat.application.identity.ClientDescriptor;
import com.fallingnight.chat.application.identity.ClientPlatform;
import com.fallingnight.chat.application.identity.IssuedSession;
import com.fallingnight.chat.application.identity.SessionResumeService;
import com.fallingnight.chat.application.security.SecretBytes;
import com.fallingnight.chat.protocol.v2.Authenticate;
import com.fallingnight.chat.protocol.v2.AuthenticationRejected;
import com.fallingnight.chat.protocol.v2.AuthenticationRejectionReason;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.ResumeSession;
import com.fallingnight.chat.protocol.v2.SessionEstablished;
import com.google.protobuf.ByteString;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class V2AuthenticationHandlerTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID DEVICE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID SESSION_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @Test
    void authenticatesOffTheTransportBoundaryAndBindsServerIdentity() throws Exception {
        byte[] token = new byte[32];
        token[0] = 42;
        SecretBytes resumeSecret = SecretBytes.copyOf(token);
        IssuedSession issued = new IssuedSession(
                ACCOUNT_ID,
                DEVICE_ID,
                SESSION_ID,
                resumeSecret,
                Instant.ofEpochMilli(NOW + 60_000),
                "Alice");
        RecordingEvents events = new RecordingEvents();
        AuthenticationUseCase useCase = command -> {
            assertEquals("alice", command.username());
            return new AuthenticationResult.Established(issued, true);
        };
        EmbeddedChannel channel = channel(useCase, events);
        try {
            assertFalse(channel.writeInbound(authenticateEnvelope(validAuthentication())));
            channel.runPendingTasks();

            Envelope response = channel.readOutbound();
            assertEquals(MessageKind.MESSAGE_KIND_RESPONSE, response.getKind());
            assertEquals(MessageType.MESSAGE_TYPE_SESSION_ESTABLISHED_VALUE,
                    response.getMessageType());
            assertEquals(SESSION_ID.toString(), response.getSessionId());
            SessionEstablished established = SessionEstablished.parseFrom(response.getPayload());
            assertEquals(ACCOUNT_ID.toString(), established.getAccountId());
            assertEquals(DEVICE_ID.toString(), established.getDeviceId());
            assertEquals(SESSION_ID.toString(), established.getSessionId());
            assertArrayEquals(token, established.getResumeToken().toByteArray());
            assertEquals("Alice", established.getDisplayName());
            assertTrue(resumeSecret.isClosed());
            assertEquals(1, events.accepted);
            assertTrue(events.upgradePending);

            AuthenticatedConnection identity = channel
                    .attr(V2ConnectionAttributes.AUTHENTICATED)
                    .get();
            assertNotNull(identity);
            assertEquals(ACCOUNT_ID, identity.accountId());
            assertEquals(SESSION_ID, identity.sessionId());

            Envelope next = Envelope.newBuilder()
                    .setProtocolVersion(2)
                    .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                    .setMessageType(99)
                    .setRequestId("next-1")
                    .setSessionId(SESSION_ID.toString())
                    .setSentAtEpochMs(NOW)
                    .build();
            assertTrue(channel.writeInbound(next));
            assertEquals(next, channel.readInbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void returnsTheSameGenericRejectionForCredentialsAndRejectedResume() throws Exception {
        RecordingEvents rejectedEvents = new RecordingEvents();
        EmbeddedChannel rejected = channel(
                command -> AuthenticationResult.Rejected.INSTANCE, rejectedEvents);
        try {
            rejected.writeInbound(authenticateEnvelope(validAuthentication()));
            rejected.runPendingTasks();
            assertRejected(rejected.readOutbound());
            assertEquals(1, rejectedEvents.rejected);
            assertFalse(rejected.isActive());
        } finally {
            rejected.finishAndReleaseAll();
        }

        EmbeddedChannel resume = channel(command -> {
            throw new AssertionError("resume must not invoke fresh authentication");
        }, new RecordingEvents());
        try {
            Envelope request = resumeEnvelope(SESSION_ID.toString(), new byte[32]);
            resume.writeInbound(request);
            assertRejected(resume.readOutbound());
            assertFalse(resume.isActive());
        } finally {
            resume.finishAndReleaseAll();
        }
    }

    @Test
    void resumesThroughTheBoundedUseCaseAndBindsTheRotatedSession() throws Exception {
        byte[] presentedToken = new byte[32];
        presentedToken[0] = 5;
        byte[] rotatedBytes = new byte[32];
        rotatedBytes[0] = 9;
        SecretBytes rotatedToken = SecretBytes.copyOf(rotatedBytes);
        SessionResumeService resumeService = new SessionResumeService(
                (sessionId, proof, client, now) -> {
                    assertEquals(SESSION_ID, sessionId);
                    assertArrayEquals(presentedToken, proof.withCopy(byte[]::clone));
                    assertEquals("client-device-1", client.clientDeviceId());
                    return java.util.Optional.of(new IssuedSession(
                            ACCOUNT_ID,
                            DEVICE_ID,
                            SESSION_ID,
                            rotatedToken,
                            Instant.ofEpochMilli(NOW + 60_000),
                            "Alice"));
                },
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        EmbeddedChannel channel = new EmbeddedChannel(new V2AuthenticationHandler(
                command -> {
                    throw new AssertionError("resume must not invoke password authentication");
                },
                resumeService,
                Runnable::run,
                AuthenticationAdmissionControl.allowAll()));
        channel.attr(V2ConnectionAttributes.NEGOTIATED_CLIENT).set(
                new ClientDescriptor("client-device-1", ClientPlatform.WEB, "0.1.0"));
        try {
            channel.writeInbound(resumeEnvelope(SESSION_ID.toString(), presentedToken));
            channel.runPendingTasks();

            Envelope response = channel.readOutbound();
            assertEquals(MessageType.MESSAGE_TYPE_SESSION_ESTABLISHED_VALUE,
                    response.getMessageType());
            SessionEstablished established = SessionEstablished.parseFrom(response.getPayload());
            assertEquals(SESSION_ID.toString(), established.getSessionId());
            assertArrayEquals(rotatedBytes, established.getResumeToken().toByteArray());
            assertEquals(SESSION_ID, channel.attr(V2ConnectionAttributes.AUTHENTICATED)
                    .get().sessionId());
            assertTrue(rotatedToken.isClosed());
            assertTrue(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void malformedResumeIdentityAndProofShareGenericRejection() throws Exception {
        EmbeddedChannel invalidId = channel(
                command -> AuthenticationResult.Rejected.INSTANCE, new RecordingEvents());
        try {
            invalidId.writeInbound(resumeEnvelope("not-a-uuid", new byte[32]));
            assertRejected(invalidId.readOutbound());
            assertFalse(invalidId.isActive());
        } finally {
            invalidId.finishAndReleaseAll();
        }

        EmbeddedChannel invalidProof = channel(
                command -> AuthenticationResult.Rejected.INSTANCE, new RecordingEvents());
        try {
            invalidProof.writeInbound(resumeEnvelope(SESSION_ID.toString(), new byte[31]));
            assertRejected(invalidProof.readOutbound());
            assertFalse(invalidProof.isActive());
        } finally {
            invalidProof.finishAndReleaseAll();
        }
    }

    @Test
    void closesOnMalformedAuthenticationAndSessionSpoofing() throws Exception {
        EmbeddedChannel malformed = channel(command -> AuthenticationResult.Rejected.INSTANCE,
                new RecordingEvents());
        try {
            Envelope request = authenticateEnvelope(validAuthentication()).toBuilder()
                    .setPayload(ByteString.copyFrom(new byte[] {(byte) 0x80}))
                    .build();
            malformed.writeInbound(request);
            assertProtocolError(
                    malformed.readOutbound(),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    "invalid authentication payload",
                    false);
            assertFalse(malformed.isActive());
        } finally {
            malformed.finishAndReleaseAll();
        }

        SecretBytes token = SecretBytes.copyOf(new byte[32]);
        EmbeddedChannel authenticated = channel(command -> new AuthenticationResult.Established(
                new IssuedSession(
                        ACCOUNT_ID,
                        DEVICE_ID,
                        SESSION_ID,
                        token,
                        Instant.ofEpochMilli(NOW + 60_000),
                        "Alice"),
                false), new RecordingEvents());
        try {
            authenticated.writeInbound(authenticateEnvelope(validAuthentication()));
            authenticated.runPendingTasks();
            authenticated.readOutbound();
            Envelope spoofed = authenticateEnvelope(validAuthentication()).toBuilder()
                    .setRequestId("spoof-1")
                    .setSessionId("40000000-0000-0000-0000-000000000004")
                    .build();
            authenticated.writeInbound(spoofed);
            assertProtocolError(
                    authenticated.readOutbound(),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "session does not match the authenticated connection",
                    false);
            assertFalse(authenticated.isActive());
        } finally {
            authenticated.finishAndReleaseAll();
        }
    }

    @Test
    void normalizesUnexpectedAuthenticationFailure() throws Exception {
        RecordingEvents events = new RecordingEvents();
        EmbeddedChannel channel = channel(command -> {
            throw new IllegalStateException("database endpoint must not leak");
        }, events);
        try {
            channel.writeInbound(authenticateEnvelope(validAuthentication()));
            channel.runPendingTasks();
            assertProtocolError(
                    channel.readOutbound(),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR,
                    "authentication is temporarily unavailable",
                    true);
            assertEquals(1, events.failed);
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsConcurrentAuthenticationAndClearsALateIssuedSecret() throws Exception {
        Queue<Runnable> work = new ArrayDeque<>();
        SecretBytes lateToken = SecretBytes.copyOf(new byte[32]);
        AuthenticationUseCase useCase = command -> new AuthenticationResult.Established(
                new IssuedSession(
                        ACCOUNT_ID,
                        DEVICE_ID,
                        SESSION_ID,
                        lateToken,
                        Instant.ofEpochMilli(NOW + 60_000),
                        "Alice"),
                false);
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        EmbeddedChannel channel = new EmbeddedChannel(new V2AuthenticationHandler(
                useCase,
                work::add,
                new RecordingEvents(),
                clock));
        channel.attr(V2ConnectionAttributes.NEGOTIATED_CLIENT).set(
                new ClientDescriptor("client-device-1", ClientPlatform.WEB, "0.1.0"));
        try {
            channel.writeInbound(authenticateEnvelope(validAuthentication()));
            assertEquals(1, work.size());
            assertFalse(lateToken.isClosed());

            channel.writeInbound(authenticateEnvelope(validAuthentication()).toBuilder()
                    .setRequestId("auth-2")
                    .build());
            assertProtocolError(
                    channel.readOutbound(),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "authentication is already in progress",
                    false);
            assertFalse(channel.isActive());

            work.remove().run();
            channel.runPendingTasks();
            assertTrue(lateToken.isClosed());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsSaturatedAuthenticationBeforeInvokingTheUseCase() throws Exception {
        RecordingEvents events = new RecordingEvents();
        AuthenticationUseCase useCase = command -> {
            throw new AssertionError("saturated work must not reach authentication");
        };
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        EmbeddedChannel channel = new EmbeddedChannel(new V2AuthenticationHandler(
                useCase,
                command -> {
                    throw new RejectedExecutionException("queue full");
                },
                events,
                clock));
        channel.attr(V2ConnectionAttributes.NEGOTIATED_CLIENT).set(
                new ClientDescriptor("client-device-1", ClientPlatform.WEB, "0.1.0"));
        try {
            channel.writeInbound(authenticateEnvelope(validAuthentication()));
            Envelope envelope = channel.readOutbound();
            assertEquals(MessageKind.MESSAGE_KIND_ERROR, envelope.getKind());
            AuthenticationRejected rejection = AuthenticationRejected.parseFrom(
                    envelope.getPayload());
            assertEquals(
                    AuthenticationRejectionReason.AUTHENTICATION_REJECTION_REASON_RATE_LIMITED,
                    rejection.getReason());
            assertEquals(V2AuthenticationHandler.SATURATION_RETRY_AFTER_MS,
                    rejection.getRetryAfterMs());
            assertEquals(1, events.saturated);
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void deniesAccountLimitedWorkBeforeCopyingOrDispatchingCredentials() throws Exception {
        RecordingEvents events = new RecordingEvents();
        AuthenticationAdmissionControl admission = new AuthenticationAdmissionControl() {
            @Override
            public AuthenticationAdmissionDecision acquire(
                    String directPeer, String presentedUsername) {
                assertEquals("198.51.100.7", directPeer);
                assertEquals("alice", presentedUsername);
                return AuthenticationAdmissionDecision.deny(
                        AuthenticationLimitDimension.ACCOUNT, 1_234);
            }

            @Override
            public AuthenticationAdmissionDecision acquireResume(String directPeer) {
                return AuthenticationAdmissionDecision.allow();
            }

            @Override
            public void recordSuccess(String presentedUsername) {
                throw new AssertionError("denied authentication cannot succeed");
            }
        };
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        EmbeddedChannel channel = new EmbeddedChannel(new V2AuthenticationHandler(
                command -> {
                    throw new AssertionError("denied work must not reach authentication");
                },
                Runnable::run,
                admission,
                events,
                clock));
        channel.attr(V2ConnectionAttributes.NEGOTIATED_CLIENT).set(
                new ClientDescriptor("client-device-1", ClientPlatform.WEB, "0.1.0"));
        channel.attr(V2ConnectionAttributes.CLIENT_PEER_ADDRESS).set("198.51.100.7");
        try {
            channel.writeInbound(authenticateEnvelope(validAuthentication()));
            Envelope envelope = channel.readOutbound();
            AuthenticationRejected rejection = AuthenticationRejected.parseFrom(
                    envelope.getPayload());
            assertEquals(
                    AuthenticationRejectionReason.AUTHENTICATION_REJECTION_REASON_RATE_LIMITED,
                    rejection.getReason());
            assertEquals(1_234, rejection.getRetryAfterMs());
            assertEquals(AuthenticationLimitDimension.ACCOUNT, events.deniedDimension);
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel(
            AuthenticationUseCase useCase, RecordingEvents events) {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        EmbeddedChannel channel = new EmbeddedChannel(new V2AuthenticationHandler(
                useCase,
                Runnable::run,
                events,
                clock));
        channel.attr(V2ConnectionAttributes.NEGOTIATED_CLIENT).set(
                new ClientDescriptor("client-device-1", ClientPlatform.WEB, "0.1.0"));
        return channel;
    }

    private static Envelope authenticateEnvelope(Authenticate payload) {
        return Envelope.newBuilder()
                .setProtocolVersion(2)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .setMessageType(MessageType.MESSAGE_TYPE_AUTHENTICATE_VALUE)
                .setRequestId("auth-1")
                .setSentAtEpochMs(NOW)
                .setPayload(payload.toByteString())
                .build();
    }

    private static Authenticate validAuthentication() {
        return Authenticate.newBuilder()
                .setUsername("alice")
                .setPasswordUtf8(ByteString.copyFromUtf8("correct horse battery staple"))
                .build();
    }

    private static Envelope resumeEnvelope(String sessionId, byte[] token) {
        ResumeSession payload = ResumeSession.newBuilder()
                .setSessionId(sessionId)
                .setResumeToken(ByteString.copyFrom(token))
                .build();
        return Envelope.newBuilder()
                .setProtocolVersion(2)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .setMessageType(MessageType.MESSAGE_TYPE_RESUME_SESSION_VALUE)
                .setRequestId("resume-1")
                .setSentAtEpochMs(NOW)
                .setPayload(payload.toByteString())
                .build();
    }

    private static void assertRejected(Envelope envelope) throws Exception {
        assertEquals(MessageKind.MESSAGE_KIND_ERROR, envelope.getKind());
        assertEquals(MessageType.MESSAGE_TYPE_AUTHENTICATION_REJECTED_VALUE,
                envelope.getMessageType());
        AuthenticationRejected rejection = AuthenticationRejected.parseFrom(envelope.getPayload());
        assertEquals(AuthenticationRejectionReason.AUTHENTICATION_REJECTION_REASON_REJECTED,
                rejection.getReason());
        assertEquals(0, rejection.getRetryAfterMs());
    }

    private static void assertProtocolError(
            Envelope envelope,
            ProtocolErrorCode code,
            String safeMessage,
            boolean retryable) throws Exception {
        assertEquals(MessageKind.MESSAGE_KIND_ERROR, envelope.getKind());
        assertEquals(MessageType.MESSAGE_TYPE_PROTOCOL_ERROR_VALUE, envelope.getMessageType());
        ProtocolError error = ProtocolError.parseFrom(envelope.getPayload());
        assertEquals(code, error.getCode());
        assertEquals(safeMessage, error.getSafeMessage());
        assertEquals(retryable, error.getRetryable());
    }

    private static final class RecordingEvents implements AuthenticationEventSink {
        private int accepted;
        private int rejected;
        private int failed;
        private int saturated;
        private boolean upgradePending;
        private AuthenticationLimitDimension deniedDimension;

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
    }
}
