package com.fallingnight.chat.application.contact;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One authoritative outgoing-block page correlated to its authenticated actor. */
public record AccountBlockDirectoryPage(
        UUID accountId,
        List<AccountBlockSummary> blocks,
        Optional<UUID> nextAfterTargetAccountId,
        boolean hasMore) {
    public AccountBlockDirectoryPage {
        Objects.requireNonNull(accountId, "accountId");
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        nextAfterTargetAccountId = Objects.requireNonNull(
                nextAfterTargetAccountId, "nextAfterTargetAccountId");
        if (blocks.size() > AccountBlockDirectoryQuery.MAX_LIMIT
                || (hasMore && blocks.isEmpty())) {
            throw new IllegalArgumentException("account block page bounds are invalid");
        }
        var targets = new HashSet<UUID>();
        UUID previous = null;
        for (AccountBlockSummary block : blocks) {
            Objects.requireNonNull(block, "block");
            UUID target = block.targetAccountId();
            if (!targets.add(target)
                    || (previous != null && previous.toString().compareTo(target.toString()) >= 0)) {
                throw new IllegalArgumentException(
                        "account block rows must be unique and target ordered");
            }
            previous = target;
        }
        Optional<UUID> expected = hasMore ? Optional.ofNullable(previous) : Optional.empty();
        if (!nextAfterTargetAccountId.equals(expected)) {
            throw new IllegalArgumentException("account block continuation is invalid");
        }
    }
}
