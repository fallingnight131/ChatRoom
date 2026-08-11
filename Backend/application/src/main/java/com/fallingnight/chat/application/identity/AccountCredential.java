package com.fallingnight.chat.application.identity;

import java.util.Objects;
import java.util.UUID;

/** Minimal credential projection; database row shapes remain outside the core. */
public record AccountCredential(
        UUID accountId,
        String displayName,
        StoredCredential credential,
        boolean enabled) {
    public AccountCredential {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(credential, "credential");
    }
}
