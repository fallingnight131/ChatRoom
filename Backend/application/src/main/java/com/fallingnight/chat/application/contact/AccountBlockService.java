package com.fallingnight.chat.application.contact;

import java.util.Objects;
import java.util.UUID;

/** Detached V2 account-block boundary; gateway and persistence composition remain off. */
public final class AccountBlockService {
    private final AccountBlockMutationPort mutations;

    public AccountBlockService(AccountBlockMutationPort mutations) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
    }

    public AccountBlockResult apply(UUID authenticatedAccountId, AccountBlockIntent intent) {
        Objects.requireNonNull(authenticatedAccountId, "authenticatedAccountId");
        Objects.requireNonNull(intent, "intent");
        if (authenticatedAccountId.equals(intent.targetAccountId())) {
            return AccountBlockResult.Rejected.SELF_BLOCK;
        }
        AccountBlockMutation mutation = new AccountBlockMutation(
                authenticatedAccountId,
                intent.targetAccountId(),
                intent.blocked(),
                intent.clientOperationId());
        AccountBlockResult result = Objects.requireNonNull(
                mutations.apply(mutation), "account block result");
        validateCorrelation(mutation, result);
        return result;
    }

    private static void validateCorrelation(
            AccountBlockMutation mutation, AccountBlockResult result) {
        if (result instanceof AccountBlockResult.Applied applied
                && (!applied.actorAccountId().equals(mutation.actorAccountId())
                || !applied.targetAccountId().equals(mutation.targetAccountId())
                || applied.blocked() != mutation.blocked()
                || !applied.clientOperationId().equals(mutation.clientOperationId()))) {
            throw new IllegalStateException("account block result does not match mutation");
        }
        if (result instanceof AccountBlockResult.OperationConflict conflict
                && !conflict.clientOperationId().equals(mutation.clientOperationId())) {
            throw new IllegalStateException("account block conflict does not match operation");
        }
    }
}
