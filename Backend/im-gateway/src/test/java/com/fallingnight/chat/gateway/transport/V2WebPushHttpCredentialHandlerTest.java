package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.identity.AuthenticatedDeviceActor;
import com.fallingnight.chat.application.notification.IssuedWebPushHttpCredential;
import com.fallingnight.chat.application.notification.WebPushDeliveryPolicy;
import com.fallingnight.chat.application.notification.WebPushHttpCredentialIssueService;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.WebPushHttpCredentialIssued;
import com.google.protobuf.ByteString;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V2WebPushHttpCredentialHandlerTest {
    private static final UUID ACCOUNT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID DEVICE = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID SESSION = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void bindsTheAuthenticatedActorAndReturnsOwnedCorrelatedSecrets() throws Exception {
        AtomicReference<AuthenticatedDeviceActor> observedActor = new AtomicReference<>();
        AtomicReference<IssuedWebPushHttpCredential> issuedReference = new AtomicReference<>();
        var service = service((actor, at) -> {
            observedActor.set(actor);
            var issued = credential(actor.sessionId());
            issuedReference.set(issued);
            return Optional.of(issued);
        });
        AtomicInteger issuedEvents = new AtomicInteger();
        EmbeddedChannel channel = channel(service, Runnable::run, true,
                events(issuedEvents));
        try {
            channel.writeInbound(command(ByteString.EMPTY, "spoofed-session", ""));
            channel.runPendingTasks();
            Envelope response = channel.readOutbound();
            assertEquals(MessageType.MESSAGE_TYPE_WEB_PUSH_HTTP_CREDENTIAL_ISSUED_VALUE,
                    response.getMessageType());
            assertEquals("request-1", response.getRequestId());
            assertEquals(SESSION.toString(), response.getSessionId());
            assertEquals(NOW.toEpochMilli(), response.getSentAtEpochMs());
            WebPushHttpCredentialIssued payload = WebPushHttpCredentialIssued.parseFrom(
                    response.getPayload());
            assertArrayEquals("a".repeat(43).getBytes(StandardCharsets.US_ASCII),
                    payload.getBearerTokenAscii().toByteArray());
            assertArrayEquals("b".repeat(43).getBytes(StandardCharsets.US_ASCII),
                    payload.getCsrfTokenAscii().toByteArray());
            assertEquals(NOW.plusSeconds(600).toEpochMilli(), payload.getExpiresAtEpochMs());
            assertEquals(new AuthenticatedDeviceActor(ACCOUNT, DEVICE, SESSION),
                    observedActor.get());
            assertTrue(issuedReference.get().isClosed());
            assertEquals(1, issuedEvents.get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsMissingCapabilityAndMalformedOrUnavailableRequestsSafely() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var available = service((actor, at) -> {
            calls.incrementAndGet(); return Optional.of(credential(actor.sessionId()));
        });
        EmbeddedChannel unauthenticated = new EmbeddedChannel(
                new V2WebPushHttpCredentialHandler(
                        available, Runnable::run, WebPushHttpCredentialEventSink.noop(), CLOCK));
        unauthenticated.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(
                Set.of(ClientCapability.CLIENT_CAPABILITY_WEB_PUSH_HTTP_CREDENTIAL));
        try {
            unauthenticated.writeInbound(command(ByteString.EMPTY, "spoofed-session", ""));
            Envelope response = unauthenticated.readOutbound();
            assertTrue(response.getSessionId().isEmpty());
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    ProtocolError.parseFrom(response.getPayload()).getCode());
            assertEquals(0, calls.get());
        } finally {
            unauthenticated.finishAndReleaseAll();
        }

        EmbeddedChannel uncapable = channel(
                available, Runnable::run, false, WebPushHttpCredentialEventSink.noop());
        try {
            uncapable.writeInbound(command(ByteString.EMPTY, SESSION.toString(), ""));
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE,
                    error(uncapable).getCode());
            assertEquals(0, calls.get());
        } finally {
            uncapable.finishAndReleaseAll();
        }

        EmbeddedChannel malformed = channel(
                available, Runnable::run, true, WebPushHttpCredentialEventSink.noop());
        try {
            malformed.writeInbound(command(ByteString.copyFrom(new byte[] {1}),
                    SESSION.toString(), ""));
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    error(malformed).getCode());
            malformed.writeInbound(command(ByteString.EMPTY, SESSION.toString(), "not-allowed"));
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    error(malformed).getCode());
            assertEquals(0, calls.get());
        } finally {
            malformed.finishAndReleaseAll();
        }

        EmbeddedChannel unavailable = channel(service((actor, at) -> Optional.empty()),
                Runnable::run, true, WebPushHttpCredentialEventSink.noop());
        try {
            unavailable.writeInbound(command(ByteString.EMPTY, SESSION.toString(), ""));
            unavailable.runPendingTasks();
            ProtocolError error = error(unavailable);
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED, error.getCode());
            assertFalse(error.getRetryable());
        } finally {
            unavailable.finishAndReleaseAll();
        }
    }

    @Test
    void mapsWorkerSaturationAndFailuresWithoutLeakingDetails() throws Exception {
        Executor rejected = task -> { throw new RejectedExecutionException("secret detail"); };
        EmbeddedChannel busy = channel(service((actor, at) -> {
            throw new AssertionError();
        }), rejected, true, WebPushHttpCredentialEventSink.noop());
        try {
            busy.writeInbound(command(ByteString.EMPTY, SESSION.toString(), ""));
            ProtocolError error = error(busy);
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED, error.getCode());
            assertTrue(error.getRetryable());
            assertFalse(error.getSafeMessage().contains("secret"));
        } finally {
            busy.finishAndReleaseAll();
        }

        EmbeddedChannel failed = channel(service((actor, at) -> {
            throw new IllegalStateException("database detail");
        }), Runnable::run, true, WebPushHttpCredentialEventSink.noop());
        try {
            failed.writeInbound(command(ByteString.EMPTY, SESSION.toString(), ""));
            failed.runPendingTasks();
            ProtocolError error = error(failed);
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR, error.getCode());
            assertTrue(error.getRetryable());
            assertFalse(error.getSafeMessage().contains("database"));
        } finally {
            failed.finishAndReleaseAll();
        }
    }

    private static WebPushHttpCredentialIssueService service(
            com.fallingnight.chat.application.notification.WebPushHttpCredentialIssuePort port) {
        return new WebPushHttpCredentialIssueService(
                new WebPushDeliveryPolicy(true), port, CLOCK);
    }

    private static IssuedWebPushHttpCredential credential(UUID sessionId) {
        return IssuedWebPushHttpCredential.copyOf(sessionId,
                "a".repeat(43).getBytes(StandardCharsets.US_ASCII),
                "b".repeat(43).getBytes(StandardCharsets.US_ASCII),
                NOW.plusSeconds(600));
    }

    private static EmbeddedChannel channel(WebPushHttpCredentialIssueService service,
            Executor executor, boolean capable, WebPushHttpCredentialEventSink events) {
        EmbeddedChannel channel = new EmbeddedChannel(
                new V2WebPushHttpCredentialHandler(service, executor, events, CLOCK));
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT, DEVICE, SESSION));
        channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(capable
                ? Set.of(ClientCapability.CLIENT_CAPABILITY_WEB_PUSH_HTTP_CREDENTIAL) : Set.of());
        return channel;
    }

    private static Envelope command(ByteString payload, String sessionId, String clientMessageId) {
        return Envelope.newBuilder().setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .setMessageType(MessageType.MESSAGE_TYPE_ISSUE_WEB_PUSH_HTTP_CREDENTIAL_VALUE)
                .setRequestId("request-1").setSessionId(sessionId)
                .setClientMessageId(clientMessageId).setPayload(payload).build();
    }

    private static ProtocolError error(EmbeddedChannel channel) throws Exception {
        Envelope response = channel.readOutbound();
        return ProtocolError.parseFrom(response.getPayload());
    }

    private static WebPushHttpCredentialEventSink events(AtomicInteger issued) {
        return new WebPushHttpCredentialEventSink() {
            @Override public void issued() { issued.incrementAndGet(); }
            @Override public void denied() { }
            @Override public void saturated() { }
            @Override public void failed() { }
        };
    }
}
