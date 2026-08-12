package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.util.HashMap;
import java.util.Map;

/** Composes text, attachment, and ordered message inputs from one physical proof. */
public final class V1UnifiedMessageImportBundleVerifier {
    public VerifiedV1UnifiedMessageImportBundle combine(
            VerifiedV1MessageStateImportInput stateInput,
            VerifiedV1MessagePayloadImportInput payloadInput,
            VerifiedV1AttachmentImportInput attachmentInput) {
        requireCompatible(stateInput, payloadInput, attachmentInput);
        return new VerifiedV1UnifiedMessageImportBundle(
                stateInput, payloadInput, attachmentInput);
    }

    static void requireCompatible(
            VerifiedV1MessageStateImportInput stateInput,
            VerifiedV1MessagePayloadImportInput payloadInput,
            VerifiedV1AttachmentImportInput attachmentInput) {
        if (!stateInput.backupProof().equals(payloadInput.backupProof())
                || !stateInput.backupProof().equals(attachmentInput.backupProof())
                || !stateInput.plan().readyToCompareWithTarget()
                || !payloadInput.plan().readyForUnifiedImport()
                || !attachmentInput.plan().readyToCompareWithTarget()) {
            throw failure();
        }

        Map<MessageKey, V1MessageCursorRow> states = new HashMap<>();
        for (V1MessageCursorRow row : stateInput.plan().sourceMessageRows()) {
            if (states.put(new MessageKey(row.legacyKind(), row.legacyMessageId()), row) != null) {
                throw failure();
            }
        }
        Map<MessageKey, PlannedV1AttachmentImport> attachments = new HashMap<>();
        for (PlannedV1AttachmentImport row : attachmentInput.plan().attachments()) {
            PlannedV1AttachmentSource source = row.source();
            if (attachments.put(new MessageKey(
                    source.legacyKind(), source.legacyMessageId()), row) != null) {
                throw failure();
            }
        }
        int expected = payloadInput.plan().messages().size()
                + payloadInput.plan().deferredAttachments().size();
        if (states.size() != expected
                || attachments.size() != payloadInput.plan().deferredAttachments().size()) {
            throw failure();
        }

        for (PlannedV1MessagePayload payload : payloadInput.plan().messages()) {
            V1MessageCursorRow state = states.get(
                    new MessageKey(payload.legacyKind(), payload.legacyMessageId()));
            if (state == null
                    || state.legacyConversationId() != payload.legacyConversationId()
                    || state.recalled() == payload.historicalContentAvailable()
                    || !payload.messageId().equals(
                            V1MessagePayloadImportPlanner.deterministicMessageId(
                                    payload.legacyKind(), payload.legacyMessageId()))) {
                throw failure();
            }
        }
        for (DeferredV1AttachmentPayload deferred
                : payloadInput.plan().deferredAttachments()) {
            MessageKey key = new MessageKey(
                    deferred.legacyKind(), deferred.legacyMessageId());
            V1MessageCursorRow state = states.get(key);
            PlannedV1AttachmentImport attachment = attachments.get(key);
            if (state == null || attachment == null
                    || !matches(deferred, state, attachment.source())) {
                throw failure();
            }
        }
    }

    private static boolean matches(
            DeferredV1AttachmentPayload deferred,
            V1MessageCursorRow state,
            PlannedV1AttachmentSource source) {
        return state.legacyConversationId() == deferred.legacyConversationId()
                && state.legacyConversationId() == source.legacyConversationId()
                && state.legacySenderUserId() == source.legacyUploaderUserId()
                && state.recalled() == deferred.recalled()
                && state.createdAt().equals(source.messageAcceptedAt())
                && deferred.legacyFileId() == source.legacyFileId()
                && deferred.legacyContentType().equals(source.legacyContentType())
                && source.messageId().equals(
                        V1MessagePayloadImportPlanner.deterministicMessageId(
                                source.legacyKind(), source.legacyMessageId()));
    }

    private static V1MessageImportBundleException failure() {
        return new V1MessageImportBundleException(
                "V1 message sequence, payload, attachment, and backup inputs do not reconcile");
    }

    private record MessageKey(LegacyV1ConversationKind kind, long id) { }
}
