package com.fallingnight.chat.application.attachment;

import java.util.Objects;

/** Result of an authorization-rechecked, idempotent READY transition. */
public sealed interface AttachmentReadyTransition {
    record Ready(RegisteredAttachment attachment, boolean changed)
            implements AttachmentReadyTransition {
        public Ready {
            Objects.requireNonNull(attachment, "attachment");
            if (attachment.state() != AttachmentState.READY) {
                throw new IllegalArgumentException("attachment must be READY");
            }
        }
    }

    enum Rejected implements AttachmentReadyTransition {
        NOT_AVAILABLE
    }
}
