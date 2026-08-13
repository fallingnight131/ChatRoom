package com.fallingnight.chat.application.profile;

public interface ProfileImageOrphanCleanupPort {
    /** Durably requests eventual deletion only when no current profile references the object. */
    void requestIfUnreferenced(ProfileImageObjectEvidence evidence);
}
