package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V1MessageImportBundleVerifierTest {
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");
    private static final VerifiedV1IdentityBackup PROOF = new VerifiedV1IdentityBackup(
            "1".repeat(64), "2".repeat(64), 1, 1024,
            Instant.parse("2026-08-12T12:00:00Z"));

    @Test
    void composesExactTypedMessageAndSharedBackupProof() {
        VerifiedV1MessageStateImportInput state = stateInput();
        VerifiedV1MessagePayloadImportInput payload = payloadInput(9);

        VerifiedV1MessageImportBundle bundle =
                new V1MessageImportBundleVerifier().combine(state, payload);

        assertEquals(PROOF, bundle.backupProof());
        assertEquals(1, bundle.statePlan().sourceMessages());
        assertEquals(1, bundle.payloadPlan().sourceRows());
    }

    @Test
    void rejectsConversationMismatchAndDifferentPhysicalProof() {
        assertThrows(V1MessageImportBundleException.class,
                () -> new V1MessageImportBundleVerifier().combine(
                        stateInput(), payloadInput(10)));
        VerifiedV1IdentityBackup otherProof = new VerifiedV1IdentityBackup(
                PROOF.sourceFingerprintSha256(), "3".repeat(64), 1, 1024,
                PROOF.createdAt());
        VerifiedV1MessagePayloadImportInput otherPayload = new VerifiedV1MessagePayloadImportInput(
                payloadInput(9).plan(), otherProof, Path.of("source"), Path.of("backup"));
        assertThrows(V1MessageImportBundleException.class,
                () -> new V1MessageImportBundleVerifier().combine(stateInput(), otherPayload));
    }

    private static VerifiedV1MessageStateImportInput stateInput() {
        V1ConversationImportPlan conversations = new V1ConversationImportPlanner().plan(
                new V1ConversationSourceSnapshot(
                        Set.of(1L),
                        List.of(new V1RoomRow(9, "Room", 1, CREATED)),
                        List.of(new V1RoomMembershipRow(9, 1, CREATED, 100)),
                        Set.of(),
                        List.of()));
        V1MessageStateImportPlan plan = new V1MessageStateImportPlanner().plan(
                new V1MessageStateSourceSnapshot(
                        conversations,
                        List.of(new V1ConversationWatermarkRow(
                                LegacyV1ConversationKind.ROOM, 9, 1)),
                        List.of(new V1MessageCursorRow(
                                LegacyV1ConversationKind.ROOM, 9, 100, 1,
                                1, null, false, CREATED)),
                        List.of()));
        return new VerifiedV1MessageStateImportInput(
                plan, PROOF, Path.of("source"), Path.of("backup"));
    }

    private static VerifiedV1MessagePayloadImportInput payloadInput(long conversationId) {
        V1MessagePayloadImportPlan plan = new V1MessagePayloadImportPlanner().plan(List.of(
                new V1MessagePayloadRow(
                        LegacyV1ConversationKind.ROOM, conversationId, 100,
                        "text", "hello", "", 0, 0, false, "", "", false)));
        return new VerifiedV1MessagePayloadImportInput(
                plan, PROOF, Path.of("source"), Path.of("backup"));
    }
}
