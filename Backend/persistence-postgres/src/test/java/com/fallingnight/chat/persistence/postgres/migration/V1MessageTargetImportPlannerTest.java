package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V1MessageTargetImportPlannerTest {
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");
    private static final VerifiedV1IdentityBackup PROOF = new VerifiedV1IdentityBackup(
            "1".repeat(64), "2".repeat(64), 1, 1024,
            Instant.parse("2026-08-12T12:00:00Z"));

    @Test
    void joinsVerifiedRowsAndReusesOneDeterministicLegacyDevicePerSender() {
        V1ConversationImportPlan conversations = new V1ConversationImportPlanner().plan(
                new V1ConversationSourceSnapshot(
                        Set.of(1L),
                        List.of(new V1RoomRow(9, "Room", 1, CREATED)),
                        List.of(new V1RoomMembershipRow(9, 1, CREATED, 101)),
                        Set.of(), List.of()));
        V1MessageStateImportPlan statePlan = new V1MessageStateImportPlanner().plan(
                new V1MessageStateSourceSnapshot(
                        conversations,
                        List.of(new V1ConversationWatermarkRow(
                                LegacyV1ConversationKind.ROOM, 9, 3)),
                        List.of(cursor(100, 1, null, false), cursor(101, 2, 3L, true)),
                        List.of()));
        V1MessagePayloadImportPlan payloadPlan = new V1MessagePayloadImportPlanner().plan(List.of(
                payload(100, "hello", false), payload(101, "recalled", true)));
        VerifiedV1MessageImportBundle bundle = new V1MessageImportBundleVerifier().combine(
                new VerifiedV1MessageStateImportInput(
                        statePlan, PROOF, Path.of("source"), Path.of("backup")),
                new VerifiedV1MessagePayloadImportInput(
                        payloadPlan, PROOF, Path.of("source"), Path.of("backup")));

        V1MessageTargetImportPlan target = new V1MessageTargetImportPlanner().plan(bundle);

        assertEquals(1, target.legacyDevices().size());
        assertEquals("v1-history-import", target.legacyDevices().getFirst().clientDeviceId());
        assertEquals(5, target.legacyDevices().getFirst().deviceId().version());
        assertEquals(List.of(1L, 2L), target.messages().stream()
                .map(PlannedV1HistoricalMessage::creationSequence).toList());
        assertEquals(4, target.conversationCursors().getFirst().targetNextSequence());
        assertFalse(target.messages().getLast().historicalContentAvailable());
        assertEquals(target.legacyDevices().getFirst().deviceId(),
                target.messages().getFirst().senderDeviceId());
    }

    private static V1MessageCursorRow cursor(
            long messageId, long sequence, Long mutation, boolean recalled) {
        return new V1MessageCursorRow(
                LegacyV1ConversationKind.ROOM, 9, messageId, 1,
                sequence, mutation, recalled, CREATED);
    }

    private static V1MessagePayloadRow payload(
            long messageId, String content, boolean recalled) {
        return new V1MessagePayloadRow(
                LegacyV1ConversationKind.ROOM, 9, messageId, "text", content,
                "", 0, 0, false, "", "", recalled);
    }
}
