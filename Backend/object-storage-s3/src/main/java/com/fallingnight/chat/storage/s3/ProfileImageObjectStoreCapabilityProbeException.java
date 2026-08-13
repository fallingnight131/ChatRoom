package com.fallingnight.chat.storage.s3;

/** Fixed, non-secret failure from the explicitly activated profile-image probe. */
final class ProfileImageObjectStoreCapabilityProbeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ProfileImageObjectStoreCapabilityProbeException(String message) { super(message); }
}
