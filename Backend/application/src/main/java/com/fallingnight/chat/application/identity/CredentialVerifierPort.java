package com.fallingnight.chat.application.identity;

import java.util.Optional;

/** Password verifier that performs dummy work when absent and reports upgrade need. */
@FunctionalInterface
public interface CredentialVerifierPort {
    CredentialVerification verifyOrDummy(
            byte[] passwordUtf8, Optional<StoredCredential> storedCredential);
}
