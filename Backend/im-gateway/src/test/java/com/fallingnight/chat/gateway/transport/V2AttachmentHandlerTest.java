package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.attachment.AttachmentActor;
import com.fallingnight.chat.application.attachment.AttachmentLifecyclePort;
import com.fallingnight.chat.application.attachment.AttachmentObjectStorePort;
import com.fallingnight.chat.application.attachment.AttachmentReadyTransition;
import com.fallingnight.chat.application.attachment.AttachmentRegistration;
import com.fallingnight.chat.application.attachment.AttachmentRegistrationResult;
import com.fallingnight.chat.application.attachment.AttachmentState;
import com.fallingnight.chat.application.attachment.AttachmentUploadGrant;
import com.fallingnight.chat.application.attachment.AttachmentUploadService;
import com.fallingnight.chat.application.attachment.AttachmentUploadTarget;
import com.fallingnight.chat.application.attachment.RegisteredAttachment;
import com.fallingnight.chat.application.attachment.StoredAttachmentObject;
import com.fallingnight.chat.protocol.v2.AttachmentReady;
import com.fallingnight.chat.protocol.v2.AttachmentRegistered;
import com.fallingnight.chat.protocol.v2.AttachmentUploadAuthorized;
import com.fallingnight.chat.protocol.v2.AuthorizeAttachmentUpload;
import com.fallingnight.chat.protocol.v2.CompleteAttachmentUpload;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.RegisterAttachment;
import com.google.protobuf.ByteString;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V2AttachmentHandlerTest {
    private static final UUID ACCOUNT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID DEVICE = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID SESSION = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID CONVERSATION = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID ATTACHMENT = UUID.fromString("50000000-0000-4000-8000-000000000005");
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final byte[] HASH = new byte[32];
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void registersUsingOnlyServerBoundIdentityAndReturnsStableDuplicate() throws Exception {
        AtomicReference<AttachmentRegistration> captured = new AtomicReference<>();
        MutableLifecycle lifecycle = new MutableLifecycle(pending());
        EmbeddedChannel channel = channel(registration -> {
            captured.set(registration);
            return new AttachmentRegistrationResult.Accepted(pending(), true);
        }, lifecycle, objectStore(true), Runnable::run, AttachmentEventSink.noop(), true);
        try {
            channel.writeInbound(registerEnvelope());
            channel.runPendingTasks();

            assertEquals(ACCOUNT, captured.get().ownerAccountId());
            assertEquals(DEVICE, captured.get().ownerDeviceId());
            assertEquals(CONVERSATION, captured.get().conversationId());
            AttachmentRegistered result = AttachmentRegistered.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload());
            assertEquals(ATTACHMENT.toString(), result.getAttachmentId());
            assertTrue(result.getDuplicate());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void authorizesWithExactTransientGrantAndCompletesReadyIdempotently() throws Exception {
        MutableLifecycle lifecycle = new MutableLifecycle(pending());
        EmbeddedChannel channel = channel(
                ignored -> AttachmentRegistrationResult.Rejected.NOT_AUTHORIZED,
                lifecycle, objectStore(true), Runnable::run, AttachmentEventSink.noop(), true);
        try {
            channel.writeInbound(authorizeEnvelope());
            channel.runPendingTasks();
            AttachmentUploadAuthorized authorized = AttachmentUploadAuthorized.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload());
            assertEquals("https://objects.example.test/key?signature=do-not-log",
                    authorized.getUploadUri());
            assertEquals(2, authorized.getRequiredHeadersCount());
            assertEquals("content-type", authorized.getRequiredHeaders(0).getName());
            assertEquals("if-none-match", authorized.getRequiredHeaders(1).getName());

            channel.writeInbound(completeEnvelope());
            channel.runPendingTasks();
            AttachmentReady ready = AttachmentReady.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload());
            assertEquals(ATTACHMENT.toString(), ready.getAttachmentId());
            assertFalse(ready.getDuplicate());
            assertEquals(NOW.toEpochMilli(), ready.getReadyAtEpochMs());

            channel.writeInbound(completeEnvelope());
            channel.runPendingTasks();
            AttachmentReady duplicate = AttachmentReady.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload());
            assertTrue(duplicate.getDuplicate());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void returnsSameOpaqueDenialForMissingOrForeignAttachment() throws Exception {
        MutableLifecycle lifecycle = new MutableLifecycle(null);
        EmbeddedChannel channel = channel(
                ignored -> AttachmentRegistrationResult.Rejected.NOT_AUTHORIZED,
                lifecycle, objectStore(false), Runnable::run, AttachmentEventSink.noop(), true);
        try {
            channel.writeInbound(authorizeEnvelope());
            channel.runPendingTasks();
            ProtocolError authorize = error(channel);
            channel.writeInbound(completeEnvelope());
            channel.runPendingTasks();
            ProtocolError complete = error(channel);

            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                    authorize.getCode());
            assertEquals(authorize, complete);
            assertFalse(authorize.getSafeMessage().contains(ATTACHMENT.toString()));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void distinguishesRetryableMissingObjectFromNonretryableIntegrityMismatch() {
        MutableLifecycle lifecycle = new MutableLifecycle(pending());
        EmbeddedChannel missing = channel(
                ignored -> AttachmentRegistrationResult.Rejected.NOT_AUTHORIZED,
                lifecycle, objectStore(false), Runnable::run, AttachmentEventSink.noop(), true);
        try {
            missing.writeInbound(completeEnvelope());
            missing.runPendingTasks();
            ProtocolError result = error(missing);
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE, result.getCode());
            assertTrue(result.getRetryable());
        } finally {
            missing.finishAndReleaseAll();
        }

        AttachmentObjectStorePort mismatch = new AttachmentObjectStorePort() {
            @Override
            public AttachmentUploadGrant issueCreateOnlyPut(
                    AttachmentUploadTarget target, Instant expiresAt) {
                throw new AssertionError("grant should not be issued");
            }

            @Override
            public Optional<StoredAttachmentObject> inspectSealedObject(
                    AttachmentUploadTarget target) {
                byte[] wrong = target.contentSha256();
                wrong[0] ^= 1;
                return Optional.of(new StoredAttachmentObject(
                        target.objectKey(), target.byteSize(), wrong));
            }
        };
        EmbeddedChannel mismatched = channel(
                ignored -> AttachmentRegistrationResult.Rejected.NOT_AUTHORIZED,
                new MutableLifecycle(pending()), mismatch, Runnable::run,
                AttachmentEventSink.noop(), true);
        try {
            mismatched.writeInbound(completeEnvelope());
            mismatched.runPendingTasks();
            ProtocolError result = error(mismatched);
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE, result.getCode());
            assertFalse(result.getRetryable());
        } finally {
            mismatched.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsUnauthenticatedWrongKindMalformedAndPassesOtherTypesDownstream() {
        MutableLifecycle lifecycle = new MutableLifecycle(pending());
        EmbeddedChannel channel = channel(
                ignored -> AttachmentRegistrationResult.Rejected.NOT_AUTHORIZED,
                lifecycle, objectStore(true), Runnable::run, AttachmentEventSink.noop(), false);
        try {
            channel.writeInbound(registerEnvelope());
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    error(channel).getCode());

            authenticate(channel);
            Envelope wrongKind = registerEnvelope().toBuilder()
                    .setKind(MessageKind.MESSAGE_KIND_RESPONSE).build();
            channel.writeInbound(wrongKind);
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    error(channel).getCode());

            Envelope malformed = authorizeEnvelope().toBuilder()
                    .setPayload(ByteString.copyFrom(new byte[] {(byte) 0xff})).build();
            channel.writeInbound(malformed);
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    error(channel).getCode());

            Envelope messaging = registerEnvelope().toBuilder()
                    .setMessageType(MessageType.MESSAGE_TYPE_SUBMIT_MESSAGE_VALUE).build();
            assertTrue(channel.writeInbound(messaging));
            assertEquals(messaging, channel.readInbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void boundsPendingWorkAndNormalizesDependencyFailureWithoutSignedUrl() {
        HoldingExecutor holding = new HoldingExecutor();
        CountingEvents events = new CountingEvents();
        MutableLifecycle lifecycle = new MutableLifecycle(pending());
        EmbeddedChannel channel = channel(registration -> {
            throw new RuntimeException("https://objects.example.test/key?signature=secret");
        }, lifecycle, objectStore(true), holding, events, true);
        try {
            for (int index = 0; index < V2AttachmentHandler.MAX_PENDING_COMMANDS + 2; index++) {
                channel.writeInbound(registerEnvelope());
            }
            ProtocolError saturated = error(channel);
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    saturated.getCode());
            assertTrue(saturated.getRetryable());
            assertEquals(1, events.saturated);

            holding.runNext();
            channel.runPendingTasks();
            ProtocolError failed = error(channel);
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR,
                    failed.getCode());
            assertFalse(failed.toString().contains("objects.example"));
            assertFalse(failed.toString().contains("signature"));
            assertEquals(1, events.failed);
        } finally {
            channel.finishAndReleaseAll();
        }

        EmbeddedChannel rejected = channel(
                ignored -> AttachmentRegistrationResult.Rejected.NOT_AUTHORIZED,
                lifecycle, objectStore(true), ignored -> {
                    throw new RejectedExecutionException();
                }, events, true);
        try {
            rejected.writeInbound(registerEnvelope());
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    error(rejected).getCode());
        } finally {
            rejected.finishAndReleaseAll();
        }
    }

    @Test
    void suppressesLateDependencyResultAfterDisconnect() {
        HoldingExecutor holding = new HoldingExecutor();
        MutableLifecycle lifecycle = new MutableLifecycle(pending());
        EmbeddedChannel channel = channel(
                ignored -> new AttachmentRegistrationResult.Accepted(pending(), false),
                lifecycle, objectStore(true), holding, AttachmentEventSink.noop(), true);

        channel.writeInbound(registerEnvelope());
        channel.close();
        holding.runNext();
        channel.runPendingTasks();

        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    private static EmbeddedChannel channel(
            com.fallingnight.chat.application.attachment.AttachmentRegistrationPort registrations,
            MutableLifecycle lifecycle,
            AttachmentObjectStorePort objects,
            Executor executor,
            AttachmentEventSink events,
            boolean authenticated) {
        AttachmentUploadService uploads = new AttachmentUploadService(
                lifecycle, objects, Duration.ofMinutes(5), CLOCK);
        EmbeddedChannel channel = new EmbeddedChannel(new V2AttachmentHandler(
                registrations, uploads, executor, events, CLOCK));
        if (authenticated) authenticate(channel);
        return channel;
    }

    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT, DEVICE, SESSION));
    }

    private static AttachmentObjectStorePort objectStore(boolean present) {
        return new AttachmentObjectStorePort() {
            @Override
            public AttachmentUploadGrant issueCreateOnlyPut(
                    AttachmentUploadTarget target, Instant expiresAt) {
                return new AttachmentUploadGrant(
                        URI.create("https://objects.example.test/key?signature=do-not-log"),
                        Map.of("if-none-match", "*", "content-type", "text/plain"),
                        expiresAt);
            }

            @Override
            public Optional<StoredAttachmentObject> inspectSealedObject(
                    AttachmentUploadTarget target) {
                return present ? Optional.of(new StoredAttachmentObject(
                        target.objectKey(), target.byteSize(), target.contentSha256()))
                        : Optional.empty();
            }
        };
    }

    private static RegisteredAttachment pending() {
        return attachment(AttachmentState.UPLOAD_PENDING, Optional.empty());
    }

    private static RegisteredAttachment attachment(
            AttachmentState state, Optional<Instant> readyAt) {
        return new RegisteredAttachment(
                ATTACHMENT, CONVERSATION, ACCOUNT, DEVICE, "client-attachment-1",
                "attachments/" + ATTACHMENT, "a.txt", "text/plain", 7, HASH,
                state, NOW.minusSeconds(60), readyAt, Optional.empty());
    }

    private static Envelope registerEnvelope() {
        RegisterAttachment payload = RegisterAttachment.newBuilder()
                .setConversationId(CONVERSATION.toString())
                .setClientAttachmentId("client-attachment-1")
                .setFileName("a.txt").setMediaType("text/plain").setByteSize(7)
                .setContentSha256(ByteString.copyFrom(HASH)).build();
        return command(MessageType.MESSAGE_TYPE_REGISTER_ATTACHMENT, payload.toByteString());
    }

    private static Envelope authorizeEnvelope() {
        return command(MessageType.MESSAGE_TYPE_AUTHORIZE_ATTACHMENT_UPLOAD,
                AuthorizeAttachmentUpload.newBuilder()
                        .setAttachmentId(ATTACHMENT.toString()).build().toByteString());
    }

    private static Envelope completeEnvelope() {
        return command(MessageType.MESSAGE_TYPE_COMPLETE_ATTACHMENT_UPLOAD,
                CompleteAttachmentUpload.newBuilder()
                        .setAttachmentId(ATTACHMENT.toString()).build().toByteString());
    }

    private static Envelope command(MessageType type, ByteString payload) {
        return Envelope.newBuilder().setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND).setMessageType(type.getNumber())
                .setRequestId("request-1").setSessionId("client-spoofed-session")
                .setClientMessageId("envelope-client-id").setPayload(payload).build();
    }

    private static ProtocolError error(EmbeddedChannel channel) {
        Envelope response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(MessageType.MESSAGE_TYPE_PROTOCOL_ERROR_VALUE, response.getMessageType());
        try {
            return ProtocolError.parseFrom(response.getPayload());
        } catch (com.google.protobuf.InvalidProtocolBufferException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class MutableLifecycle implements AttachmentLifecyclePort {
        private RegisteredAttachment attachment;

        private MutableLifecycle(RegisteredAttachment attachment) {
            this.attachment = attachment;
        }

        @Override
        public Optional<RegisteredAttachment> findAuthorized(
                UUID attachmentId, AttachmentActor actor) {
            if (attachment == null || !actor.accountId().equals(ACCOUNT)
                    || !actor.deviceId().equals(DEVICE)) {
                return Optional.empty();
            }
            return Optional.of(attachment);
        }

        @Override
        public AttachmentReadyTransition markReadyIfAuthorized(
                UUID attachmentId, AttachmentActor actor, Instant readyAt) {
            if (attachment == null) return AttachmentReadyTransition.Rejected.NOT_AVAILABLE;
            if (attachment.state() == AttachmentState.READY) {
                return new AttachmentReadyTransition.Ready(attachment, false);
            }
            attachment = attachment(AttachmentState.READY, Optional.of(readyAt));
            return new AttachmentReadyTransition.Ready(attachment, true);
        }
    }

    private static final class HoldingExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.addLast(command); }
        private void runNext() { tasks.removeFirst().run(); }
    }

    private static final class CountingEvents implements AttachmentEventSink {
        private int saturated;
        private int failed;
        @Override public void registered(boolean duplicate) { }
        @Override public void uploadAuthorized() { }
        @Override public void ready(boolean duplicate) { }
        @Override public void denied() { }
        @Override public void conflict() { }
        @Override public void invalid() { }
        @Override public void saturated() { saturated++; }
        @Override public void failed() { failed++; }
    }
}
