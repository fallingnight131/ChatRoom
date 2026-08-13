package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.identity.StoredCredential;
import java.util.Objects;
import java.util.UUID;

public record LegacyV1PasswordChangeIntent(UUID actorAccountId, UUID currentSessionId,
        StoredCredential expectedCredential, StoredCredential.Argon2id replacementCredential) {
    public LegacyV1PasswordChangeIntent {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(currentSessionId, "currentSessionId");
        Objects.requireNonNull(expectedCredential, "expectedCredential");
        Objects.requireNonNull(replacementCredential, "replacementCredential");
    }
}
