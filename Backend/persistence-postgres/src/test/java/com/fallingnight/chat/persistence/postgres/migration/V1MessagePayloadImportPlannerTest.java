package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V1MessagePayloadImportPlannerTest {
    @Test
    void mapsTextAndEmojiDeterministicallyWithoutClaimingRecalledOriginalContent() {
        V1MessagePayloadRow text = row(
                LegacyV1ConversationKind.ROOM, 9, 100, "text", "hello", false);
        V1MessagePayloadRow recalledEmoji = row(
                LegacyV1ConversationKind.FRIENDSHIP, 4, 100, "emoji", "撤回占位", true);

        V1MessagePayloadImportPlan first =
                new V1MessagePayloadImportPlanner().plan(List.of(text, recalledEmoji));
        V1MessagePayloadImportPlan reordered =
                new V1MessagePayloadImportPlanner().plan(List.of(recalledEmoji, text));

        assertTrue(first.readyToCompareWithTarget());
        assertEquals(first, reordered);
        assertEquals(2, first.messages().size());
        assertTrue(first.messages().stream().allMatch(row -> row.targetContentType() == 1));
        assertTrue(first.messages().stream().anyMatch(
                row -> row.targetClientMessageId().equals("v1-import-room-100")));
        assertTrue(first.messages().stream().anyMatch(
                row -> !row.historicalContentAvailable()));
        assertNotEquals(
                V1MessagePayloadImportPlanner.deterministicMessageId(
                        LegacyV1ConversationKind.ROOM, 100),
                V1MessagePayloadImportPlanner.deterministicMessageId(
                        LegacyV1ConversationKind.FRIENDSHIP, 100));
    }

    @Test
    void blocksAttachmentsUnknownTypesOversizeAndDuplicateSourceIdsWithoutLeakingContent() {
        String secret = "private-attachment-name";
        V1MessagePayloadImportPlan plan = new V1MessagePayloadImportPlanner().plan(List.of(
                row(LegacyV1ConversationKind.ROOM, 9, 1, "file", secret, false),
                row(LegacyV1ConversationKind.ROOM, 9, 2, "system", secret, false),
                row(LegacyV1ConversationKind.ROOM, 9, 3, "text", "x".repeat(65_537), false),
                row(LegacyV1ConversationKind.ROOM, 10, 1, "text", "duplicate", false)));

        assertFalse(plan.readyToCompareWithTarget());
        Set<String> codes = plan.issues().stream()
                .map(V1MessagePayloadImportIssue::code)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.contains("ATTACHMENT_MAPPING_REQUIRED"));
        assertTrue(codes.contains("UNSUPPORTED_CONTENT_TYPE"));
        assertTrue(codes.contains("INVALID_TEXT_CONTENT"));
        assertTrue(codes.contains("INVALID_OR_DUPLICATE_MESSAGE_ID"));
        assertFalse(plan.issues().toString().contains(secret));
    }

    private static V1MessagePayloadRow row(
            LegacyV1ConversationKind kind,
            long conversationId,
            long messageId,
            String contentType,
            String content,
            boolean recalled) {
        return new V1MessagePayloadRow(
                kind, conversationId, messageId, contentType, content,
                "", 0, 0, false, "", "", recalled);
    }
}
