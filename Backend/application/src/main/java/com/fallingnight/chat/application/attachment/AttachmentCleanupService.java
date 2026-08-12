package com.fallingnight.chat.application.attachment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Runs one bounded, retryable revoke-delete-confirm attachment cleanup pass. */
public final class AttachmentCleanupService {
    public static final int MAX_BATCH_SIZE = 1_000;
    public static final Duration MIN_PENDING_AGE = Duration.ofMinutes(10);

    private final AttachmentCleanupPort attachments;
    private final AttachmentObjectDeletionPort objects;
    private final Duration pendingAge;
    private final int batchSize;
    private final Clock clock;

    public AttachmentCleanupService(
            AttachmentCleanupPort attachments,
            AttachmentObjectDeletionPort objects,
            Duration pendingAge,
            int batchSize,
            Clock clock) {
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.pendingAge = Objects.requireNonNull(pendingAge, "pendingAge");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (pendingAge.compareTo(MIN_PENDING_AGE) < 0
                || pendingAge.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("pendingAge must be in [10 minutes, 30 days]");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be in 1..1000");
        }
        this.batchSize = batchSize;
    }

    public AttachmentCleanupReport runOnce() {
        Instant now = clock.instant();
        int revoked = attachments.revokeExpiredPending(
                now.minus(pendingAge), now, batchSize);
        List<AttachmentCleanupCandidate> candidates = List.copyOf(
                attachments.findObjectCleanupRequired(batchSize));
        if (candidates.size() > batchSize) {
            throw new IllegalStateException("cleanup port exceeded requested batch size");
        }
        int deleted = 0;
        int providerFailures = 0;
        int confirmationFailures = 0;
        for (AttachmentCleanupCandidate candidate : candidates) {
            try {
                objects.deleteIfPresent(candidate.objectKey());
            } catch (RuntimeException exception) {
                providerFailures++;
                continue;
            }
            try {
                if (attachments.confirmObjectDeleted(candidate.attachmentId(), now)) {
                    deleted++;
                } else {
                    confirmationFailures++;
                }
            } catch (RuntimeException exception) {
                confirmationFailures++;
            }
        }
        return new AttachmentCleanupReport(
                revoked, candidates.size(), deleted,
                providerFailures, confirmationFailures);
    }
}
