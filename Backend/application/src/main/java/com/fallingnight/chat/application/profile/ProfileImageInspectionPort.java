package com.fallingnight.chat.application.profile;

import java.util.Optional;

/** Decodes under resource limits and re-encodes accepted input to canonical PNG. */
public interface ProfileImageInspectionPort {
    Optional<CanonicalProfileImage> inspect(LegacyV1AvatarUpload upload);
}
