package com.fallingnight.chat.storage.s3;

/** Fixed, non-secret failure from the explicitly activated provider probe. */
final class AttachmentObjectStoreCapabilityProbeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    AttachmentObjectStoreCapabilityProbeException(String message) {
        super(message);
    }
}
