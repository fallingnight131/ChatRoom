package com.fallingnight.chat.application.profile;

import java.util.Objects;

public record ProfileImageObjectWriteResult(ProfileImageObjectEvidence evidence,
        boolean created) {
    public ProfileImageObjectWriteResult {
        Objects.requireNonNull(evidence, "evidence");
    }
}
