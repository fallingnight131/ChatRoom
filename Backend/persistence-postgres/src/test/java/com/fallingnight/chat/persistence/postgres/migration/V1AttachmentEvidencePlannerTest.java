package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V1AttachmentEvidencePlannerTest {
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");
    private static final Instant ACCEPTED = CREATED.plusSeconds(1);
    private static final Instant CLEARED = CREATED.plusSeconds(2);
    private static final Instant SEALED = CREATED.plusSeconds(3);

    @Test
    void bindsActiveObjectEvidenceAndKeepsClearedHistoryObjectFree() {
        V1AttachmentSourcePlan source = sourcePlan();
        PlannedV1AttachmentSource active = source.attachments().stream()
                .filter(row -> !row.cleared()).findFirst().orElseThrow();
        byte[] hash = new byte[32];
        hash[0] = 7;
        V1AttachmentObjectEvidence evidence = evidence(active, hash);
        V1AttachmentEvidencePlanner planner = new V1AttachmentEvidencePlanner();

        V1AttachmentImportPlan first = planner.plan(source,
                new V1AttachmentObjectEvidenceBundle(
                        source.sourceFingerprintSha256(), List.of(evidence)));
        V1AttachmentImportPlan second = planner.plan(source,
                new V1AttachmentObjectEvidenceBundle(
                        source.sourceFingerprintSha256(), List.of(evidence)));

        assertTrue(first.readyToCompareWithTarget());
        assertEquals(first.sourceFingerprintSha256(), second.sourceFingerprintSha256());
        assertEquals(first.evidenceFingerprintSha256(), second.evidenceFingerprintSha256());
        assertEquals(2, first.attachments().size());
        PlannedV1AttachmentImport ready = first.attachments().stream()
                .filter(row -> !row.unavailable()).findFirst().orElseThrow();
        assertEquals("attachments/" + active.attachmentId(), ready.objectKey().orElseThrow());
        assertEquals(SEALED, ready.readyAt().orElseThrow());
        PlannedV1AttachmentImport unavailable = first.attachments().stream()
                .filter(PlannedV1AttachmentImport::unavailable).findFirst().orElseThrow();
        assertEquals(CLEARED, unavailable.unavailableAt().orElseThrow());
        assertEquals("legacy-v1-file-cleared", unavailable.unavailableReason().orElseThrow());
        assertTrue(unavailable.objectKey().isEmpty());
        assertTrue(unavailable.mediaType().isEmpty());
        assertTrue(unavailable.contentSha256().isEmpty());

        hash[0] = 99;
        assertEquals(7, evidence.contentSha256()[0]);
        assertEquals(7, ready.contentSha256().orElseThrow()[0]);
    }

    @Test
    void blocksFingerprintCoverageDuplicateClearedAndUnknownEvidence() {
        V1AttachmentSourcePlan source = sourcePlan();
        PlannedV1AttachmentSource active = source.attachments().stream()
                .filter(row -> !row.cleared()).findFirst().orElseThrow();
        PlannedV1AttachmentSource cleared = source.attachments().stream()
                .filter(PlannedV1AttachmentSource::cleared).findFirst().orElseThrow();
        V1AttachmentObjectEvidence activeEvidence = evidence(active, new byte[32]);
        V1AttachmentObjectEvidence clearedEvidence = evidence(cleared, new byte[32]);
        V1AttachmentObjectEvidence unknown = new V1AttachmentObjectEvidence(
                LegacyV1ConversationKind.ROOM, 999, "attachments/unknown",
                "application/octet-stream", 1, new byte[32], SEALED);

        V1AttachmentImportPlan plan = new V1AttachmentEvidencePlanner().plan(source,
                new V1AttachmentObjectEvidenceBundle("0".repeat(64),
                        List.of(activeEvidence, activeEvidence, clearedEvidence, unknown)));

        assertFalse(plan.readyToCompareWithTarget());
        Set<String> codes = plan.issues().stream().map(V1AttachmentImportIssue::code)
                .collect(Collectors.toSet());
        assertTrue(codes.contains("SOURCE_FINGERPRINT_MISMATCH"));
        assertTrue(codes.contains("DUPLICATE_OBJECT_EVIDENCE"));
        assertTrue(codes.contains("EVIDENCE_FOR_CLEARED_FILE"));
        assertTrue(codes.contains("UNKNOWN_OBJECT_EVIDENCE"));
    }

    @Test
    void blocksEveryUntrustedObjectAttributeAndDetectsEvidenceDrift() {
        V1AttachmentSourcePlan source = sourcePlan();
        PlannedV1AttachmentSource active = source.attachments().stream()
                .filter(row -> !row.cleared()).findFirst().orElseThrow();
        V1AttachmentEvidencePlanner planner = new V1AttachmentEvidencePlanner();
        V1AttachmentObjectEvidence invalid = new V1AttachmentObjectEvidence(
                active.legacyKind(), active.legacyFileId(), "legacy/local/path",
                "invalid", active.byteSize() + 1, new byte[31], CREATED.minusSeconds(1));

        V1AttachmentImportPlan rejected = planner.plan(source,
                new V1AttachmentObjectEvidenceBundle(
                        source.sourceFingerprintSha256(), List.of(invalid)));
        Set<String> codes = rejected.issues().stream().map(V1AttachmentImportIssue::code)
                .collect(Collectors.toSet());
        assertTrue(codes.contains("OBJECT_KEY_MISMATCH"));
        assertTrue(codes.contains("OBJECT_SIZE_MISMATCH"));
        assertTrue(codes.contains("INVALID_OBJECT_MEDIA_TYPE"));
        assertTrue(codes.contains("INVALID_OBJECT_SHA256"));
        assertTrue(codes.contains("INVALID_OBJECT_SEALED_AT"));
        assertFalse(rejected.issues().toString().contains("legacy/local/path"));

        V1AttachmentImportPlan valid = planner.plan(source,
                new V1AttachmentObjectEvidenceBundle(source.sourceFingerprintSha256(),
                        List.of(evidence(active, new byte[32]))));
        byte[] changedHash = new byte[32];
        changedHash[0] = 1;
        V1AttachmentImportPlan changed = planner.plan(source,
                new V1AttachmentObjectEvidenceBundle(source.sourceFingerprintSha256(),
                        List.of(evidence(active, changedHash))));
        assertNotEquals(valid.evidenceFingerprintSha256(),
                changed.evidenceFingerprintSha256());
    }

    private static V1AttachmentSourcePlan sourcePlan() {
        V1AttachmentSourceFile active = file(
                LegacyV1ConversationKind.ROOM, 10, 20, 30, false);
        V1AttachmentSourceFile cleared = file(
                LegacyV1ConversationKind.FRIENDSHIP, 11, 21, 31, true);
        return new V1AttachmentSourcePlanner().plan(
                List.of(active, cleared), List.of(link(active, 40), link(cleared, 41)));
    }

    private static V1AttachmentSourceFile file(LegacyV1ConversationKind kind,
            long conversation, long file, long user, boolean cleared) {
        return new V1AttachmentSourceFile(kind, conversation, file, user,
                cleared ? "expired.pdf" : "active.pdf", 123, cleared,
                cleared ? "expired" : "", cleared ? CLEARED : null, CREATED,
                "/private/source", "https://legacy.invalid/secret");
    }

    private static V1AttachmentMessageLink link(V1AttachmentSourceFile file, long message) {
        return new V1AttachmentMessageLink(file.legacyKind(), file.legacyConversationId(),
                message, file.legacyUploaderUserId(), file.legacyFileId(), "file",
                file.fileName(), file.byteSize(), file.cleared(), file.clearReason(), ACCEPTED);
    }

    private static V1AttachmentObjectEvidence evidence(
            PlannedV1AttachmentSource source, byte[] hash) {
        return new V1AttachmentObjectEvidence(source.legacyKind(), source.legacyFileId(),
                "attachments/" + source.attachmentId(), "application/pdf",
                source.byteSize(), hash, SEALED);
    }
}
