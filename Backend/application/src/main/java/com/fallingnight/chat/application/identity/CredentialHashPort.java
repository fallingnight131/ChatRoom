package com.fallingnight.chat.application.identity;

/** Creates a current-policy Argon2id credential from short-lived password bytes. */
@FunctionalInterface
public interface CredentialHashPort {
    StoredCredential.Argon2id hash(byte[] passwordUtf8);
}
