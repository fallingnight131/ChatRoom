package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Server-bound recipient authorization for the V1 accept command. */
public final class LegacyV1FriendRequestAcceptanceService
        implements LegacyV1FriendRequestAcceptanceUseCase {
    private final LegacyV1FriendRequestAcceptancePort decisions;

    public LegacyV1FriendRequestAcceptanceService(
            LegacyV1FriendRequestAcceptancePort decisions) {
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    @Override
    public LegacyV1FriendRequestAcceptanceResult accept(
            UUID accountId, long legacyRequestId) {
        Objects.requireNonNull(accountId, "accountId");
        if (legacyRequestId <= 0 || legacyRequestId > Integer.MAX_VALUE) {
            return LegacyV1FriendRequestAcceptanceResult.Rejected.INSTANCE;
        }
        LegacyV1FriendRequestAcceptanceResult result = Objects.requireNonNull(
                decisions.accept(legacyRequestId, accountId), "decision result");
        if (result instanceof LegacyV1FriendRequestAcceptanceResult.Accepted accepted
                && accepted.requesterAccountId().equals(accountId)) {
            throw new IllegalStateException("friend request requester equals recipient");
        }
        return result;
    }
}
