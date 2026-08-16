package com.fallingnight.chat.application.contact;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authenticated actor's bounded outgoing-block directory query. */
public record AccountBlockDirectoryQuery(
        UUID accountId,
        Optional<UUID> afterTargetAccountId,
        int limit) {
    public static final int MAX_LIMIT = 100;

    public AccountBlockDirectoryQuery {
        Objects.requireNonNull(accountId, "accountId");
        afterTargetAccountId = Objects.requireNonNull(
                afterTargetAccountId, "afterTargetAccountId");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be in 1..100");
        }
    }
}
