package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.identity.StoredCredential;
import java.util.Objects;

public record LegacyV1RegistrationIntent(String username, String displayName,
        StoredCredential.Argon2id credential) {
    public LegacyV1RegistrationIntent {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(credential, "credential");
    }
}
