package com.fallingnight.chat.application.identity;

import java.util.UUID;

/** Compare-and-set replacement so concurrent password changes are never overwritten. */
@FunctionalInterface
public interface CredentialUpgradePort {
    boolean replace(
            UUID accountId,
            StoredCredential expected,
            StoredCredential.Argon2id replacement);
}
