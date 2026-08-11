package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.identity.AccountCredential;
import com.fallingnight.chat.application.identity.AuthenticationEligibilityPolicy;
import java.util.Objects;

/** Allows V1 session issuance only for accounts carrying an exact V1 mapping. */
public final class LegacyV1AuthenticationEligibilityPolicy
        implements AuthenticationEligibilityPolicy {
    private final LegacyV1AccountProjectionPort identities;

    public LegacyV1AuthenticationEligibilityPolicy(LegacyV1AccountProjectionPort identities) {
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    @Override
    public boolean mayEstablish(AccountCredential account) {
        Objects.requireNonNull(account, "account");
        return identities.findByAccountId(account.accountId())
                .filter(identity -> identity.accountId().equals(account.accountId()))
                .isPresent();
    }
}
