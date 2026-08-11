package com.fallingnight.chat.application.identity;

/** Optional product-boundary policy checked after credential verification. */
@FunctionalInterface
public interface AuthenticationEligibilityPolicy {
    boolean mayEstablish(AccountCredential account);

    static AuthenticationEligibilityPolicy allowAll() {
        return account -> true;
    }
}
