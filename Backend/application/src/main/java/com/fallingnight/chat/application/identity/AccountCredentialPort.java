package com.fallingnight.chat.application.identity;

import java.util.Optional;

/** Outward account-credential lookup implemented by durable persistence. */
@FunctionalInterface
public interface AccountCredentialPort {
    Optional<AccountCredential> findByPresentedUsername(String username);
}
