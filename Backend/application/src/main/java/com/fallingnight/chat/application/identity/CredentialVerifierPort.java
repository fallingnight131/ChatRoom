package com.fallingnight.chat.application.identity;

import java.util.Optional;

/** Password verifier that must perform equivalent dummy work when no hash exists. */
@FunctionalInterface
public interface CredentialVerifierPort {
    boolean matchesOrDummy(byte[] passwordUtf8, Optional<String> storedHash);
}
