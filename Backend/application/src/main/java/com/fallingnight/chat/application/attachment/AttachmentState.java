package com.fallingnight.chat.application.attachment;

/** Durable metadata lifecycle; object access remains a separate authorization. */
public enum AttachmentState {
    UPLOAD_PENDING,
    READY,
    REVOKED,
    UNAVAILABLE
}
