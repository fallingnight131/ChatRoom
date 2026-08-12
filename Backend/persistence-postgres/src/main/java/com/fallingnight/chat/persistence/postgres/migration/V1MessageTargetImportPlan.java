package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;

/** Deterministic target-ready text message/device plan; still performs no writes. */
public record V1MessageTargetImportPlan(
        String stateFingerprintSha256,
        String payloadFingerprintSha256,
        String attachmentSourceFingerprintSha256,
        String attachmentEvidenceFingerprintSha256,
        List<PlannedV1LegacyDevice> legacyDevices,
        List<PlannedV1AttachmentImport> attachments,
        List<PlannedV1HistoricalMessage> messages,
        List<PlannedV1DeletionEvent> deletionEvents,
        List<PlannedV1ConversationCursor> conversationCursors,
        List<PlannedV1MemberReadCursor> memberReadCursors) {
    public V1MessageTargetImportPlan {
        java.util.Objects.requireNonNull(stateFingerprintSha256, "stateFingerprintSha256");
        java.util.Objects.requireNonNull(payloadFingerprintSha256, "payloadFingerprintSha256");
        java.util.Objects.requireNonNull(attachmentSourceFingerprintSha256,
                "attachmentSourceFingerprintSha256");
        java.util.Objects.requireNonNull(attachmentEvidenceFingerprintSha256,
                "attachmentEvidenceFingerprintSha256");
        legacyDevices = List.copyOf(legacyDevices);
        attachments = List.copyOf(attachments);
        messages = List.copyOf(messages);
        deletionEvents = List.copyOf(deletionEvents);
        conversationCursors = List.copyOf(conversationCursors);
        memberReadCursors = List.copyOf(memberReadCursors);
    }
}
