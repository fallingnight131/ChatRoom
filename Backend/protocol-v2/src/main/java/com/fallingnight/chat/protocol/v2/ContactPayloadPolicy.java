package com.fallingnight.chat.protocol.v2;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.UUID;

/** Structural validation for the authenticated account-block wire contract. */
public final class ContactPayloadPolicy {
    public static final int MAX_ACCOUNT_BLOCK_PAGE_SIZE = 100;
    public static final int MAX_DISPLAY_NAME_UTF8_BYTES = 256;

    private ContactPayloadPolicy() { }

    public static void requireValid(SetAccountBlock command) {
        requireUuid(command.getTargetAccountId(), "targetAccountId");
        requireUuid(command.getClientOperationId(), "clientOperationId");
    }

    public static void requireValid(AccountBlockApplied response) {
        requireUuid(response.getActorAccountId(), "actorAccountId");
        requireUuid(response.getTargetAccountId(), "targetAccountId");
        requireUuid(response.getClientOperationId(), "clientOperationId");
        if (response.getActorAccountId().equals(response.getTargetAccountId())) {
            throw new IllegalArgumentException("account block response is self-directed");
        }
    }

    public static void requireValid(ListAccountBlocks command) {
        if (!command.getAfterTargetAccountId().isEmpty()) {
            requireUuid(command.getAfterTargetAccountId(), "afterTargetAccountId");
        }
        if (command.getLimit() < 1 || command.getLimit() > MAX_ACCOUNT_BLOCK_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid account block page limit");
        }
    }

    public static void requireValid(AccountBlockSummary summary) {
        requireUuid(summary.getTargetAccountId(), "targetAccountId");
        int displayNameBytes = summary.getTargetDisplayName()
                .getBytes(StandardCharsets.UTF_8).length;
        if (displayNameBytes < 1 || displayNameBytes > MAX_DISPLAY_NAME_UTF8_BYTES) {
            throw new IllegalArgumentException("invalid target display name");
        }
        if (summary.getBlockedAtEpochMs() <= 0) {
            throw new IllegalArgumentException("invalid blockedAtEpochMs");
        }
    }

    public static void requireValid(AccountBlockDirectoryPage page) {
        if (page.getBlocksCount() > MAX_ACCOUNT_BLOCK_PAGE_SIZE) {
            throw new IllegalArgumentException("too many account block rows");
        }
        var targets = new HashSet<String>();
        String previous = "";
        for (AccountBlockSummary summary : page.getBlocksList()) {
            requireValid(summary);
            String target = summary.getTargetAccountId();
            if (!targets.add(target) || (!previous.isEmpty() && previous.compareTo(target) >= 0)) {
                throw new IllegalArgumentException("account block rows must be unique and ordered");
            }
            previous = target;
        }
        if (page.getHasMore()) {
            requireUuid(page.getNextAfterTargetAccountId(), "nextAfterTargetAccountId");
            if (page.getBlocksCount() == 0
                    || !page.getNextAfterTargetAccountId().equals(previous)) {
                throw new IllegalArgumentException("invalid account block continuation");
            }
        } else if (!page.getNextAfterTargetAccountId().isEmpty()) {
            throw new IllegalArgumentException("terminal account block page has continuation");
        }
    }

    private static void requireUuid(String value, String name) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException(name + " is not canonical");
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " is not a UUID", exception);
        }
    }
}
