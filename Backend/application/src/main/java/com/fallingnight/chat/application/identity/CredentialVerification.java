package com.fallingnight.chat.application.identity;

/** Credential outcome without exposing account existence to the transport. */
public enum CredentialVerification {
    REJECTED,
    VERIFIED,
    VERIFIED_NEEDS_UPGRADE
}
