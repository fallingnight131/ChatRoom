package com.fallingnight.chat.application.contact;

import java.util.Objects;
import java.util.UUID;

/** Binds the directory actor to authentication and rejects adapter miscorrelation. */
public final class AccountBlockDirectoryService implements AccountBlockDirectoryUseCase {
    private final AccountBlockDirectoryPort directory;

    public AccountBlockDirectoryService(AccountBlockDirectoryPort directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public AccountBlockDirectoryResult list(
            UUID authenticatedAccountId, AccountBlockDirectoryRequest request) {
        Objects.requireNonNull(authenticatedAccountId, "authenticatedAccountId");
        Objects.requireNonNull(request, "request");
        var query = new AccountBlockDirectoryQuery(
                authenticatedAccountId, request.afterTargetAccountId(), request.limit());
        AccountBlockDirectoryResult result = Objects.requireNonNull(
                directory.list(query), "account block directory result");
        if (result instanceof AccountBlockDirectoryResult.Found found
                && (!found.page().accountId().equals(authenticatedAccountId)
                || found.page().blocks().size() > request.limit())) {
            throw new IllegalStateException("account block directory result is miscorrelated");
        }
        return result;
    }
}
