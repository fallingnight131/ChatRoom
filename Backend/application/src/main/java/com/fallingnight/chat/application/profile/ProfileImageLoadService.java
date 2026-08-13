package com.fallingnight.chat.application.profile;

import java.util.Objects;

/** Authorizes through durable metadata before loading and verifying private bytes. */
public final class ProfileImageLoadService implements ProfileImageLoadUseCase {
    private final ProfileImageReadPort metadata;
    private final ProfileImageObjectReadPort objects;

    public ProfileImageLoadService(ProfileImageReadPort metadata,
            ProfileImageObjectReadPort objects) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    @Override public ProfileImageLoadResult load(ProfileImageReadTarget target) {
        Objects.requireNonNull(target, "target");
        ProfileImageReadResult result = metadata.read(target);
        if (result == ProfileImageReadResult.Missing.INSTANCE)
            return ProfileImageLoadResult.Missing.INSTANCE;
        if (result == ProfileImageReadResult.Rejected.ACCESS_DENIED)
            return ProfileImageLoadResult.Rejected.ACCESS_DENIED;

        ProfileImageReadResult.Found found = (ProfileImageReadResult.Found) result;
        ProfileImageObjectPayload payload = objects.read(found.object()).orElseThrow(
                () -> new ProfileImageIntegrityException(
                        "profile image metadata references a missing private object"));
        try {
            if (payload.byteSize() != found.object().byteSize())
                throw new ProfileImageIntegrityException("profile image byte length mismatch");
            payload.withCopy(bytes -> {
                try {
                    new CanonicalProfileImage(bytes, found.width(), found.height(),
                            found.object().contentSha256());
                } catch (IllegalArgumentException exception) {
                    throw new ProfileImageIntegrityException(
                            "profile image bytes do not match durable evidence", exception);
                }
                return null;
            });
            return new ProfileImageLoadResult.Loaded(payload, found.width(), found.height(),
                    found.version(), found.updatedAt());
        } catch (RuntimeException exception) {
            payload.close();
            throw exception;
        }
    }
}
