package com.fallingnight.chat.application.profile;

@FunctionalInterface
public interface ProfileImageMutationUseCase {
    /** Takes ownership of {@code upload}; caller owns and must close a committed result. */
    ProfileImageMutationResult change(ProfileImageTarget target, LegacyV1AvatarUpload upload);
}
