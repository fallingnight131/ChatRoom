package com.fallingnight.chat.application.profile;

import java.util.Optional;

public interface ProfileImageObjectReadPort {
    Optional<ProfileImageObjectPayload> read(ProfileImageObjectEvidence evidence);
}
