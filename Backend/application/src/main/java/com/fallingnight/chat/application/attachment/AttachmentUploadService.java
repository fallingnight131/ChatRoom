package com.fallingnight.chat.application.attachment;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Coordinates short-lived upload grants and fail-closed READY transitions. */
public final class AttachmentUploadService {
    public static final Duration MAX_GRANT_LIFETIME = Duration.ofMinutes(10);

    private final AttachmentLifecyclePort attachments;
    private final AttachmentObjectStorePort objectStore;
    private final Duration grantLifetime;
    private final Clock clock;

    public AttachmentUploadService(
            AttachmentLifecyclePort attachments,
            AttachmentObjectStorePort objectStore,
            Duration grantLifetime,
            Clock clock) {
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
        this.grantLifetime = Objects.requireNonNull(grantLifetime, "grantLifetime");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (grantLifetime.isZero() || grantLifetime.isNegative()
                || grantLifetime.compareTo(MAX_GRANT_LIFETIME) > 0) {
            throw new IllegalArgumentException("grantLifetime must be in (0, 10 minutes]");
        }
    }

    public AttachmentUploadAuthorizationResult authorizeUpload(
            UUID attachmentId, AttachmentActor actor) {
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(actor, "actor");
        Optional<RegisteredAttachment> found = attachments.findAuthorized(attachmentId, actor);
        if (found.isEmpty() || found.orElseThrow().state() != AttachmentState.UPLOAD_PENDING) {
            return AttachmentUploadAuthorizationResult.Rejected.NOT_AVAILABLE;
        }
        RegisteredAttachment attachment = found.orElseThrow();
        Instant now = clock.instant();
        Instant maximumExpiry = now.plus(grantLifetime);
        AttachmentUploadGrant grant = objectStore.issueCreateOnlyPut(
                AttachmentUploadTarget.from(attachment), maximumExpiry);
        if (!grant.expiresAt().isAfter(now) || grant.expiresAt().isAfter(maximumExpiry)) {
            throw new IllegalStateException("object store returned an invalid grant lifetime");
        }
        return new AttachmentUploadAuthorizationResult.Granted(attachment, grant);
    }

    public AttachmentCompletionResult completeUpload(
            UUID attachmentId, AttachmentActor actor) {
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(actor, "actor");
        Optional<RegisteredAttachment> found = attachments.findAuthorized(attachmentId, actor);
        if (found.isEmpty() || found.orElseThrow().state() == AttachmentState.REVOKED) {
            return AttachmentCompletionResult.Rejected.NOT_AVAILABLE;
        }
        RegisteredAttachment attachment = found.orElseThrow();
        if (attachment.state() == AttachmentState.READY) {
            return new AttachmentCompletionResult.Ready(attachment, true);
        }
        AttachmentUploadTarget target = AttachmentUploadTarget.from(attachment);
        Optional<StoredAttachmentObject> stored = objectStore.inspectSealedObject(target);
        if (stored.isEmpty()) {
            return AttachmentCompletionResult.Rejected.OBJECT_MISSING;
        }
        if (!matches(target, stored.orElseThrow())) {
            return AttachmentCompletionResult.Rejected.OBJECT_MISMATCH;
        }
        Instant readyAt = clock.instant();
        if (readyAt.isBefore(attachment.createdAt())) {
            throw new IllegalStateException("server clock precedes attachment creation");
        }
        AttachmentReadyTransition transitioned = attachments.markReadyIfAuthorized(
                attachmentId, actor, readyAt);
        if (transitioned instanceof AttachmentReadyTransition.Ready ready) {
            return new AttachmentCompletionResult.Ready(
                    ready.attachment(), !ready.changed());
        }
        return AttachmentCompletionResult.Rejected.NOT_AVAILABLE;
    }

    private static boolean matches(
            AttachmentUploadTarget expected, StoredAttachmentObject actual) {
        return expected.objectKey().equals(actual.objectKey())
                && expected.byteSize() == actual.byteSize()
                && MessageDigest.isEqual(
                        expected.contentSha256(), actual.contentSha256());
    }
}
