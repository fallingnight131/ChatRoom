package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V1MessageStateImportPlannerTest {
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void preservesAllocatedRangesAndTranslatesTableIdsToCreationSequences() {
        V1ConversationImportPlan conversations = conversationPlan();
        V1MessageStateSourceSnapshot source = new V1MessageStateSourceSnapshot(
                conversations,
                List.of(
                        new V1ConversationWatermarkRow(
                                LegacyV1ConversationKind.ROOM, 9, 8),
                        new V1ConversationWatermarkRow(
                                LegacyV1ConversationKind.FRIENDSHIP, 4, 3)),
                List.of(
                        message(LegacyV1ConversationKind.ROOM, 9, 100, 1, 2, null, false),
                        message(LegacyV1ConversationKind.ROOM, 9, 105, 2, 4, 7L, true),
                        message(LegacyV1ConversationKind.FRIENDSHIP, 4, 50, 1, 1, null, false),
                        message(LegacyV1ConversationKind.FRIENDSHIP, 4, 60, 2, 3, null, false)),
                List.of(new V1RoomDeletionCursorRow(1, 9, 1, 6, CREATED)));

        V1MessageStateImportPlan first = new V1MessageStateImportPlanner().plan(source);
        V1MessageStateImportPlan reordered = new V1MessageStateImportPlanner().plan(
                new V1MessageStateSourceSnapshot(
                        conversations,
                        source.watermarks().reversed(),
                        source.messages().reversed(),
                        source.roomDeletionEvents()));

        assertTrue(first.readyToCompareWithTarget());
        assertEquals(first, reordered);
        assertEquals(List.of(3L, 8L), first.conversationCursors().stream()
                .map(PlannedV1ConversationCursor::legacyLastSequence)
                .sorted().toList());
        assertEquals(List.of(4L, 9L), first.conversationCursors().stream()
                .map(PlannedV1ConversationCursor::targetNextSequence)
                .sorted().toList());

        var byPointer = first.memberReadCursors().stream().collect(
                java.util.stream.Collectors.toMap(
                        PlannedV1MemberReadCursor::legacyLastReadMessageId,
                        PlannedV1MemberReadCursor::targetLastReadSequence));
        assertEquals(0, byPointer.get(0L));
        assertEquals(2, byPointer.get(100L));
        assertEquals(4, byPointer.get(999L));
        assertEquals(1, byPointer.get(55L));
        assertEquals(3, byPointer.get(60L));
    }

    @Test
    void blocksCursorCollisionsMissingWatermarksAndInconsistentRecallState() {
        V1ConversationImportPlan conversations = conversationPlan();
        V1MessageStateImportPlan plan = new V1MessageStateImportPlanner().plan(
                new V1MessageStateSourceSnapshot(
                        conversations,
                        List.of(new V1ConversationWatermarkRow(
                                LegacyV1ConversationKind.ROOM, 9, 2)),
                        List.of(
                                message(LegacyV1ConversationKind.ROOM,
                                        9, 100, 1, 2, 2L, false),
                                message(LegacyV1ConversationKind.ROOM,
                                        9, 101, 2, 2, null, false)),
                        List.of(new V1RoomDeletionCursorRow(1, 9, 1, 2, CREATED))));

        assertFalse(plan.readyToCompareWithTarget());
        Set<String> codes = plan.issues().stream()
                .map(V1MessageStateImportIssue::code)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.contains("MISSING_WATERMARK"));
        assertTrue(codes.contains("DUPLICATE_CONVERSATION_SEQUENCE"));
        assertTrue(codes.contains("MUTATION_NOT_AFTER_CREATION"));
        assertTrue(codes.contains("INCONSISTENT_RECALL_STATE"));
    }

    @Test
    void blocksHistoricalSenderWhoIsNotAConversationMember() {
        V1MessageStateImportPlan plan = new V1MessageStateImportPlanner().plan(
                new V1MessageStateSourceSnapshot(
                        conversationPlan(),
                        List.of(
                                new V1ConversationWatermarkRow(
                                        LegacyV1ConversationKind.ROOM, 9, 1),
                                new V1ConversationWatermarkRow(
                                        LegacyV1ConversationKind.FRIENDSHIP, 4, 0)),
                        List.of(message(
                                LegacyV1ConversationKind.ROOM, 9, 100, 99,
                                1, null, false)),
                        List.of()));

        assertFalse(plan.readyToCompareWithTarget());
        assertTrue(plan.issues().stream().anyMatch(
                issue -> "MESSAGE_SENDER_NOT_MEMBER".equals(issue.code())));
    }

    private static V1MessageCursorRow message(
            LegacyV1ConversationKind kind,
            long conversationId,
            long messageId,
            long senderId,
            long sequence,
            Long mutationSequence,
            boolean recalled) {
        return new V1MessageCursorRow(
                kind, conversationId, messageId, senderId, sequence,
                mutationSequence, recalled, CREATED);
    }

    private static V1ConversationImportPlan conversationPlan() {
        return new V1ConversationImportPlanner().plan(new V1ConversationSourceSnapshot(
                Set.of(1L, 2L, 3L),
                List.of(new V1RoomRow(9, "Room", 1, CREATED)),
                List.of(
                        new V1RoomMembershipRow(9, 1, CREATED, 100),
                        new V1RoomMembershipRow(9, 2, CREATED, 999),
                        new V1RoomMembershipRow(9, 3, CREATED, 0)),
                Set.of(),
                List.of(new V1FriendshipRow(4, 1, 2, CREATED, 55, 60))));
    }
}
