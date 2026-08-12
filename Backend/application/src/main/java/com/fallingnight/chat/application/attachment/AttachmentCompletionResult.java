package com.fallingnight.chat.application.attachment;

import java.util.Objects;

/** Completion result after trusted object metadata and authorization checks. */
public sealed interface AttachmentCompletionResult {
    record Ready(RegisteredAttachment attachment, boolean duplicate)
            implements AttachmentCompletionResult {
        public Ready {
            Objects.requireNonNull(attachment, "attachment");
            if (attachment.state() != AttachmentState.READY) {
                throw new IllegalArgumentException("attachment must be READY");
            }
        }
    }

    enum Rejected implements AttachmentCompletionResult {
        NOT_AVAILABLE,
        OBJECT_MISSING,
        OBJECT_MISMATCH
    }
}
