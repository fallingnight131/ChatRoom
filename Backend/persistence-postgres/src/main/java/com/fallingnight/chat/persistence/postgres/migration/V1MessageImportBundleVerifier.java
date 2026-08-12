package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.util.HashMap;
import java.util.Map;

/** Composes independently verified sequence and content inputs without target writes. */
public final class V1MessageImportBundleVerifier {
    public VerifiedV1MessageImportBundle combine(
            VerifiedV1MessageStateImportInput stateInput,
            VerifiedV1MessagePayloadImportInput payloadInput) {
        requireCompatible(stateInput, payloadInput);
        return new VerifiedV1MessageImportBundle(stateInput, payloadInput);
    }

    static void requireCompatible(
            VerifiedV1MessageStateImportInput stateInput,
            VerifiedV1MessagePayloadImportInput payloadInput) {
        if (!stateInput.backupProof().equals(payloadInput.backupProof())
                || !stateInput.plan().readyToCompareWithTarget()
                || !payloadInput.plan().readyToCompareWithTarget()) {
            throw failure();
        }
        Map<MessageKey, V1MessageCursorRow> stateRows = new HashMap<>();
        for (V1MessageCursorRow row : stateInput.plan().sourceMessageRows()) {
            stateRows.put(new MessageKey(row.legacyKind(), row.legacyMessageId()), row);
        }
        if (stateRows.size() != stateInput.plan().sourceMessageRows().size()
                || stateRows.size() != payloadInput.plan().messages().size()) {
            throw failure();
        }
        for (PlannedV1MessagePayload payload : payloadInput.plan().messages()) {
            V1MessageCursorRow state = stateRows.get(
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
    }

    private static V1MessageImportBundleException failure() {
        return new V1MessageImportBundleException(
                "V1 message sequence, payload, and backup inputs do not reconcile");
    }

    private record MessageKey(LegacyV1ConversationKind kind, long id) {}
}
