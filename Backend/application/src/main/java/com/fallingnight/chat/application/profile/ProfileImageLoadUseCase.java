package com.fallingnight.chat.application.profile;

@FunctionalInterface
public interface ProfileImageLoadUseCase {
    ProfileImageLoadResult load(ProfileImageReadTarget target);
}
