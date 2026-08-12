package com.fallingnight.chat.persistence.postgres.migration;

import java.util.Objects;

/** Composed capability required by the future atomic V1 message target writer. */
public final class VerifiedV1MessageImportBundle {
    private final VerifiedV1MessageStateImportInput stateInput;
    private final VerifiedV1MessagePayloadImportInput payloadInput;

    VerifiedV1MessageImportBundle(
            VerifiedV1MessageStateImportInput stateInput,
            VerifiedV1MessagePayloadImportInput payloadInput) {
        this.stateInput = Objects.requireNonNull(stateInput, "stateInput");
        this.payloadInput = Objects.requireNonNull(payloadInput, "payloadInput");
    }

    public V1MessageStateImportPlan statePlan() {
        return stateInput.plan();
    }

    public V1MessagePayloadImportPlan payloadPlan() {
        return payloadInput.plan();
    }

    public VerifiedV1IdentityBackup backupProof() {
        return stateInput.backupProof();
    }

    void reverify() {
        stateInput.reverify();
        payloadInput.reverify();
        V1MessageImportBundleVerifier.requireCompatible(stateInput, payloadInput);
    }
}
