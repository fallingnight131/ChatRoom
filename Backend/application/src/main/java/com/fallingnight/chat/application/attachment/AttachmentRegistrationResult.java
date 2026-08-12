package com.fallingnight.chat.application.attachment;

import java.util.Objects;

/** Exact pending reservation, opaque denial, or conflicting idempotency reuse. */
public sealed interface AttachmentRegistrationResult {
    record Accepted(RegisteredAttachment attachment, boolean duplicate)
            implements AttachmentRegistrationResult {
        public Accepted {
            Objects.requireNonNull(attachment, "attachment");
        }
    }

    enum Rejected implements AttachmentRegistrationResult {
        NOT_AUTHORIZED,
        IDEMPOTENCY_CONFLICT
    }
}
