package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V1UnifiedMessageImportBundleVerifierTest {
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");
    private static final VerifiedV1IdentityBackup PROOF = new VerifiedV1IdentityBackup(
            "1".repeat(64), "2".repeat(64), 1, 1024,
            Instant.parse("2026-08-13T12:00:00Z"));

    @Test
    void composesExactTextAttachmentSequenceAndSharedBackupProof() {
        Fixture fixture = fixture(9, 1, "file", false);

        VerifiedV1UnifiedMessageImportBundle bundle =
                new V1UnifiedMessageImportBundleVerifier().combine(
                        fixture.state(), fixture.payload(), fixture.attachment());

        assertEquals(PROOF, bundle.backupProof());
        assertEquals(1, bundle.statePlan().sourceMessages());
        assertEquals(1, bundle.payloadPlan().deferredAttachments().size());
        assertEquals(1, bundle.attachmentPlan().attachments().size());
    }

    @Test
    void rejectsFileConversationTypeRecallTimeAndPhysicalProofDrift() {
        Fixture valid = fixture(9, 1, "file", false);
        Fixture wrongConversation = fixture(10, 1, "file", false);
        Fixture wrongFile = fixture(9, 2, "file", false);
        Fixture wrongType = fixture(9, 1, "image", false);
        Fixture wrongRecall = fixture(9, 1, "file", true);

        assertRejected(valid.state(), wrongConversation.payload(), valid.attachment());
        assertRejected(valid.state(), wrongFile.payload(), valid.attachment());
        assertRejected(valid.state(), wrongType.payload(), valid.attachment());
        assertRejected(valid.state(), wrongRecall.payload(), valid.attachment());

        VerifiedV1IdentityBackup otherProof = new VerifiedV1IdentityBackup(
                PROOF.sourceFingerprintSha256(), "3".repeat(64), 1, 1024,
                PROOF.createdAt());
        VerifiedV1AttachmentImportInput otherAttachment =
                new VerifiedV1AttachmentImportInput(valid.attachment().plan(), otherProof,
                        valid.attachment().evidence(), Path.of("source"), Path.of("backup"));
        assertRejected(valid.state(), valid.payload(), otherAttachment);
    }

    private static void assertRejected(
            VerifiedV1MessageStateImportInput state,
            VerifiedV1MessagePayloadImportInput payload,
            VerifiedV1AttachmentImportInput attachment) {
        assertThrows(V1MessageImportBundleException.class,
                () -> new V1UnifiedMessageImportBundleVerifier().combine(
                        state, payload, attachment));
    }

    private static Fixture fixture(
            long payloadConversation, long payloadFile, String payloadType, boolean recalled) {
        V1ConversationImportPlan conversations = new V1ConversationImportPlanner().plan(
                new V1ConversationSourceSnapshot(Set.of(1L),
                        List.of(new V1RoomRow(9, "Room", 1, 50, CREATED)),
                        List.of(new V1RoomMembershipRow(9, 1, CREATED, 100)),
                        Set.of(), List.of()));
        V1MessageStateImportPlan statePlan = new V1MessageStateImportPlanner().plan(
                new V1MessageStateSourceSnapshot(conversations,
                        List.of(new V1ConversationWatermarkRow(
                                LegacyV1ConversationKind.ROOM, 9, 1)),
                        List.of(new V1MessageCursorRow(
                                LegacyV1ConversationKind.ROOM, 9, 100, 1,
                                1, recalled ? 2L : null, recalled, CREATED)), List.of()));
        VerifiedV1MessageStateImportInput state = new VerifiedV1MessageStateImportInput(
                statePlan, PROOF, Path.of("source"), Path.of("backup"));
        V1MessagePayloadImportPlan payloadPlan = new V1MessagePayloadImportPlanner().plan(
                List.of(new V1MessagePayloadRow(LegacyV1ConversationKind.ROOM,
                        payloadConversation, 100, payloadType, "ignored", "a.pdf",
                        7, payloadFile, false, "", "", recalled)));
        VerifiedV1MessagePayloadImportInput payload = new VerifiedV1MessagePayloadImportInput(
                payloadPlan, PROOF, Path.of("source"), Path.of("backup"));
        PlannedV1AttachmentSource source = new PlannedV1AttachmentSource(
                LegacyV1ConversationKind.ROOM, 9, 1, 100, 1,
                V1ConversationImportPlanner.deterministicRoomId(9),
                V1AttachmentSourcePlanner.deterministicAttachmentId(
                        LegacyV1ConversationKind.ROOM, 1),
                V1MessagePayloadImportPlanner.deterministicMessageId(
                        LegacyV1ConversationKind.ROOM, 100),
                V1IdentityImportPlanner.deterministicUserId(1),
                V1MessageTargetImportPlanner.deterministicLegacyDeviceId(
                        V1IdentityImportPlanner.deterministicUserId(1)),
                "v1-import-room-file-1", "a.pdf", 7, "file", false, "", null,
                CREATED.minusSeconds(1), CREATED);
        PlannedV1AttachmentImport planned = new PlannedV1AttachmentImport(
                source, Optional.of("attachments/" + source.attachmentId()),
                Optional.of("application/pdf"), Optional.of(new byte[32]),
                Optional.of(CREATED), Optional.empty(), Optional.empty());
        V1AttachmentImportPlan attachmentPlan = new V1AttachmentImportPlan(
                "a".repeat(64), "b".repeat(64), 1, 1, List.of(planned), List.of());
        V1AttachmentObjectEvidenceBundle evidence =
                new V1AttachmentObjectEvidenceBundle("a".repeat(64), List.of());
        VerifiedV1AttachmentImportInput attachment = new VerifiedV1AttachmentImportInput(
                attachmentPlan, PROOF, evidence, Path.of("source"), Path.of("backup"));
        return new Fixture(state, payload, attachment);
    }

    private record Fixture(
            VerifiedV1MessageStateImportInput state,
            VerifiedV1MessagePayloadImportInput payload,
            VerifiedV1AttachmentImportInput attachment) { }
}
