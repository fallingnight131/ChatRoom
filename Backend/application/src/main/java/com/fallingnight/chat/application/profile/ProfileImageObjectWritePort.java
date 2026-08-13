package com.fallingnight.chat.application.profile;

public interface ProfileImageObjectWritePort {
    ProfileImageObjectWriteResult storeIfAbsent(CanonicalProfileImage image);
}
