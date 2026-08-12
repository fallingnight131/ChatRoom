package com.fallingnight.chat.application.attachment;

import java.util.Objects;

/** Generic authorization result that does not reveal foreign attachment existence. */
public sealed interface AttachmentUploadAuthorizationResult {
    record Granted(RegisteredAttachment attachment, AttachmentUploadGrant grant)
            implements AttachmentUploadAuthorizationResult {
        public Granted {
            Objects.requireNonNull(attachment, "attachment");
            Objects.requireNonNull(grant, "grant");
            if (attachment.state() != AttachmentState.UPLOAD_PENDING) {
                throw new IllegalArgumentException("only pending attachments may upload");
            }
        }
    }

    enum Rejected implements AttachmentUploadAuthorizationResult {
        NOT_AVAILABLE
    }
}
