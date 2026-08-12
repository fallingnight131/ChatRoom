package com.fallingnight.chat.persistence.postgres.migration;

import java.util.Objects;

/** Re-verifiable capability for one ordered text-and-attachment message import. */
public final class VerifiedV1UnifiedMessageImportBundle {
    private final VerifiedV1MessageStateImportInput stateInput;
    private final VerifiedV1MessagePayloadImportInput payloadInput;
    private final VerifiedV1AttachmentImportInput attachmentInput;

    VerifiedV1UnifiedMessageImportBundle(
            VerifiedV1MessageStateImportInput stateInput,
            VerifiedV1MessagePayloadImportInput payloadInput,
            VerifiedV1AttachmentImportInput attachmentInput) {
        this.stateInput = Objects.requireNonNull(stateInput, "stateInput");
        this.payloadInput = Objects.requireNonNull(payloadInput, "payloadInput");
        this.attachmentInput = Objects.requireNonNull(attachmentInput, "attachmentInput");
    }

    public V1MessageStateImportPlan statePlan() {
        return stateInput.plan();
    }

    public V1MessagePayloadImportPlan payloadPlan() {
        return payloadInput.plan();
    }

    public V1AttachmentImportPlan attachmentPlan() {
        return attachmentInput.plan();
    }

    public VerifiedV1IdentityBackup backupProof() {
        return stateInput.backupProof();
    }

    void reverify() {
        stateInput.reverify();
        payloadInput.reverify();
        attachmentInput.reverify();
        V1UnifiedMessageImportBundleVerifier.requireCompatible(
                stateInput, payloadInput, attachmentInput);
    }
}
