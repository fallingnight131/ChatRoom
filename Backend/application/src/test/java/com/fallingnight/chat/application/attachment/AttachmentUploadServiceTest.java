package com.fallingnight.chat.application.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AttachmentUploadServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final UUID ATTACHMENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final AttachmentActor ACTOR = new AttachmentActor(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("00000000-0000-0000-0000-000000000003"));

    @Test
    void grantsOnlyPendingOwnedAttachmentWithBoundedExpiry() {
        RegisteredAttachment pending = attachment(AttachmentState.UPLOAD_PENDING);
        MutableLifecycle lifecycle = new MutableLifecycle(pending);
        AtomicReference<AttachmentUploadTarget> grantedFor = new AtomicReference<>();
        AttachmentObjectStorePort objects = new StubObjectStore() {
            @Override
            public AttachmentUploadGrant issueCreateOnlyPut(
                    AttachmentUploadTarget target, Instant expiresAt) {
                grantedFor.set(target);
                assertEquals(NOW.plusSeconds(300), expiresAt);
                return grant(expiresAt);
            }
        };
        AttachmentUploadService service = service(lifecycle, objects);

        AttachmentUploadAuthorizationResult.Granted result = assertInstanceOf(
                AttachmentUploadAuthorizationResult.Granted.class,
                service.authorizeUpload(ATTACHMENT_ID, ACTOR));

        assertEquals(pending, result.attachment());
        assertEquals("attachments/" + ATTACHMENT_ID, grantedFor.get().objectKey());
        assertEquals(NOW.plusSeconds(300), result.grant().expiresAt());
    }

    @Test
    void refusesReadyForeignAndOverlongOrInvalidGrants() {
        AtomicBoolean issued = new AtomicBoolean();
        AttachmentObjectStorePort objects = new StubObjectStore() {
            @Override
            public AttachmentUploadGrant issueCreateOnlyPut(
                    AttachmentUploadTarget target, Instant expiresAt) {
                issued.set(true);
                return grant(expiresAt);
            }
        };
        AttachmentUploadService ready = service(
                new MutableLifecycle(attachment(AttachmentState.READY)), objects);
        assertSame(AttachmentUploadAuthorizationResult.Rejected.NOT_AVAILABLE,
                ready.authorizeUpload(ATTACHMENT_ID, ACTOR));
        AttachmentUploadService foreign = service(new MutableLifecycle(null), objects);
        assertSame(AttachmentUploadAuthorizationResult.Rejected.NOT_AVAILABLE,
                foreign.authorizeUpload(ATTACHMENT_ID, ACTOR));
        assertTrue(!issued.get());

        assertThrows(IllegalArgumentException.class, () -> new AttachmentUploadService(
                new MutableLifecycle(null), objects, Duration.ofMinutes(11), clock()));
        AttachmentObjectStorePort invalidExpiry = new StubObjectStore() {
            @Override
            public AttachmentUploadGrant issueCreateOnlyPut(
                    AttachmentUploadTarget target, Instant expiresAt) {
                return grant(expiresAt.plusSeconds(1));
            }
        };
        assertThrows(IllegalStateException.class, () -> service(
                new MutableLifecycle(attachment(AttachmentState.UPLOAD_PENDING)), invalidExpiry)
                .authorizeUpload(ATTACHMENT_ID, ACTOR));
    }

    @Test
    void verifiesExactSealedObjectBeforeMarkingReady() {
        RegisteredAttachment pending = attachment(AttachmentState.UPLOAD_PENDING);
        MutableLifecycle lifecycle = new MutableLifecycle(pending);
        AttachmentObjectStorePort objects = new StubObjectStore() {
            @Override
            public Optional<StoredAttachmentObject> inspectSealedObject(
                    AttachmentUploadTarget target) {
                return Optional.of(new StoredAttachmentObject(
                        target.objectKey(), target.byteSize(), target.contentSha256()));
            }
        };

        AttachmentCompletionResult.Ready result = assertInstanceOf(
                AttachmentCompletionResult.Ready.class,
                service(lifecycle, objects).completeUpload(ATTACHMENT_ID, ACTOR));

        assertEquals(AttachmentState.READY, result.attachment().state());
        assertTrue(!result.duplicate());
        assertEquals(NOW, result.attachment().readyAt().orElseThrow());
        assertEquals(1, lifecycle.transitionAttempts);
    }

    @Test
    void missingOrMismatchedObjectNeverTransitions() {
        RegisteredAttachment pending = attachment(AttachmentState.UPLOAD_PENDING);
        MutableLifecycle missingLifecycle = new MutableLifecycle(pending);
        assertSame(AttachmentCompletionResult.Rejected.OBJECT_MISSING,
                service(missingLifecycle, new StubObjectStore())
                        .completeUpload(ATTACHMENT_ID, ACTOR));
        assertEquals(0, missingLifecycle.transitionAttempts);

        MutableLifecycle mismatchLifecycle = new MutableLifecycle(pending);
        AttachmentObjectStorePort mismatch = new StubObjectStore() {
            @Override
            public Optional<StoredAttachmentObject> inspectSealedObject(
                    AttachmentUploadTarget target) {
                return Optional.of(new StoredAttachmentObject(
                        target.objectKey(), target.byteSize() + 1, target.contentSha256()));
            }
        };
        assertSame(AttachmentCompletionResult.Rejected.OBJECT_MISMATCH,
                service(mismatchLifecycle, mismatch).completeUpload(ATTACHMENT_ID, ACTOR));
        assertEquals(0, mismatchLifecycle.transitionAttempts);
    }

    @Test
    void completionIsIdempotentAndFailsClosedWhenAuthorizationChanges() {
        RegisteredAttachment ready = attachment(AttachmentState.READY);
        MutableLifecycle alreadyReady = new MutableLifecycle(ready);
        AttachmentCompletionResult.Ready duplicate = assertInstanceOf(
                AttachmentCompletionResult.Ready.class,
                service(alreadyReady, new StubObjectStore())
                        .completeUpload(ATTACHMENT_ID, ACTOR));
        assertTrue(duplicate.duplicate());

        MutableLifecycle revokedDuringInspection = new MutableLifecycle(
                attachment(AttachmentState.UPLOAD_PENDING));
        revokedDuringInspection.rejectTransition = true;
        AttachmentObjectStorePort exact = new StubObjectStore() {
            @Override
            public Optional<StoredAttachmentObject> inspectSealedObject(
                    AttachmentUploadTarget target) {
                return Optional.of(new StoredAttachmentObject(
                        target.objectKey(), target.byteSize(), target.contentSha256()));
            }
        };
        assertSame(AttachmentCompletionResult.Rejected.NOT_AVAILABLE,
                service(revokedDuringInspection, exact)
                        .completeUpload(ATTACHMENT_ID, ACTOR));
    }

    private static AttachmentUploadService service(
            AttachmentLifecyclePort lifecycle, AttachmentObjectStorePort objects) {
        return new AttachmentUploadService(
                lifecycle, objects, Duration.ofMinutes(5), clock());
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static AttachmentUploadGrant grant(Instant expiresAt) {
        return new AttachmentUploadGrant(
                URI.create("https://objects.example.test/upload?temporary=secret"),
                Map.of("x-checksum-sha256", "expected"), expiresAt);
    }

    private static RegisteredAttachment attachment(AttachmentState state) {
        return new RegisteredAttachment(
                ATTACHMENT_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                ACTOR.accountId(), ACTOR.deviceId(), "client-1",
                "attachments/" + ATTACHMENT_ID, "report.pdf", "application/pdf",
                1024, new byte[32], state, NOW.minusSeconds(60),
                state == AttachmentState.READY ? Optional.of(NOW) : Optional.empty(),
                state == AttachmentState.REVOKED ? Optional.of(NOW) : Optional.empty());
    }

    private static class StubObjectStore implements AttachmentObjectStorePort {
        @Override
        public AttachmentUploadGrant issueCreateOnlyPut(
                AttachmentUploadTarget target, Instant expiresAt) {
            throw new AssertionError("upload grant should not be issued");
        }

        @Override
        public Optional<StoredAttachmentObject> inspectSealedObject(
                AttachmentUploadTarget target) {
            return Optional.empty();
        }
    }

    private static final class MutableLifecycle implements AttachmentLifecyclePort {
        private RegisteredAttachment value;
        private int transitionAttempts;
        private boolean rejectTransition;

        private MutableLifecycle(RegisteredAttachment value) {
            this.value = value;
        }

        @Override
        public Optional<RegisteredAttachment> findAuthorized(
                UUID attachmentId, AttachmentActor actor) {
            return Optional.ofNullable(value);
        }

        @Override
        public AttachmentReadyTransition markReadyIfAuthorized(
                UUID attachmentId, AttachmentActor actor, Instant readyAt) {
            transitionAttempts++;
            if (rejectTransition || value == null) {
                return AttachmentReadyTransition.Rejected.NOT_AVAILABLE;
            }
            if (value.state() == AttachmentState.READY) {
                return new AttachmentReadyTransition.Ready(value, false);
            }
            value = new RegisteredAttachment(
                    value.attachmentId(), value.conversationId(), value.ownerAccountId(),
                    value.ownerDeviceId(), value.clientAttachmentId(), value.objectKey(),
                    value.fileName(), value.mediaType(), value.byteSize(),
                    value.contentSha256(), AttachmentState.READY, value.createdAt(),
                    Optional.of(readyAt), Optional.empty());
            return new AttachmentReadyTransition.Ready(value, true);
        }
    }
}
