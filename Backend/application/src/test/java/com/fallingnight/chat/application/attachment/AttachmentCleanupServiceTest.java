package com.fallingnight.chat.application.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttachmentCleanupServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final AttachmentCleanupCandidate FIRST = new AttachmentCleanupCandidate(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "attachments/first");
    private static final AttachmentCleanupCandidate SECOND = new AttachmentCleanupCandidate(
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "attachments/second");
    private static final AttachmentCleanupCandidate THIRD = new AttachmentCleanupCandidate(
            UUID.fromString("00000000-0000-0000-0000-000000000003"), "attachments/third");

    @Test
    void revokesPagesDeletesAndConfirmsInRecoverableOrder() {
        RecordingCleanup persistence = new RecordingCleanup(List.of(FIRST, SECOND));
        List<String> deleted = new ArrayList<>();
        AttachmentCleanupService service = service(persistence, deleted::add, 25);

        AttachmentCleanupReport report = service.runOnce();

        assertEquals(NOW.minus(Duration.ofHours(1)), persistence.cutoff);
        assertEquals(NOW, persistence.revokedAt);
        assertEquals(25, persistence.requestedLimit);
        assertEquals(List.of("attachments/first", "attachments/second"), deleted);
        assertEquals(List.of(FIRST.attachmentId(), SECOND.attachmentId()),
                persistence.confirmed);
        assertEquals(new AttachmentCleanupReport(2, 2, 2, 0, 0), report);
    }

    @Test
    void isolatesProviderAndConfirmationFailuresForRetry() {
        RecordingCleanup persistence = new RecordingCleanup(
                List.of(FIRST, SECOND, THIRD));
        persistence.rejectConfirmation = SECOND.attachmentId();
        AttachmentCleanupService service = service(persistence, objectKey -> {
            if (objectKey.equals(THIRD.objectKey())) {
                throw new IllegalStateException("provider unavailable");
            }
        }, 3);

        AttachmentCleanupReport report = service.runOnce();

        assertEquals(new AttachmentCleanupReport(3, 3, 1, 1, 1), report);
        assertEquals(List.of(FIRST.attachmentId()), persistence.confirmed);
    }

    @Test
    void rejectsUnsafePolicyAndUnboundedPersistenceResult() {
        assertThrows(IllegalArgumentException.class, () -> new AttachmentCleanupService(
                new RecordingCleanup(List.of()), objectKey -> {}, Duration.ofMinutes(9),
                1, clock()));
        assertThrows(IllegalArgumentException.class, () -> new AttachmentCleanupService(
                new RecordingCleanup(List.of()), objectKey -> {}, Duration.ofHours(1),
                1001, clock()));
        RecordingCleanup oversized = new RecordingCleanup(List.of(FIRST, SECOND));
        assertThrows(IllegalStateException.class,
                () -> service(oversized, objectKey -> {}, 1).runOnce());
    }

    private static AttachmentCleanupService service(
            AttachmentCleanupPort persistence,
            AttachmentObjectDeletionPort objects,
            int batchSize) {
        return new AttachmentCleanupService(
                persistence, objects, Duration.ofHours(1), batchSize, clock());
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static final class RecordingCleanup implements AttachmentCleanupPort {
        private final List<AttachmentCleanupCandidate> candidates;
        private final List<UUID> confirmed = new ArrayList<>();
        private Instant cutoff;
        private Instant revokedAt;
        private int requestedLimit;
        private UUID rejectConfirmation;

        private RecordingCleanup(List<AttachmentCleanupCandidate> candidates) {
            this.candidates = candidates;
        }

        @Override
        public int revokeExpiredPending(
                Instant createdAtOrBefore, Instant revokedAt, int limit) {
            cutoff = createdAtOrBefore;
            this.revokedAt = revokedAt;
            requestedLimit = limit;
            return candidates.size();
        }

        @Override
        public List<AttachmentCleanupCandidate> findObjectCleanupRequired(int limit) {
            return candidates;
        }

        @Override
        public boolean confirmObjectDeleted(UUID attachmentId, Instant deletedAt) {
            if (attachmentId.equals(rejectConfirmation)) {
                return false;
            }
            confirmed.add(attachmentId);
            return true;
        }
    }
}
