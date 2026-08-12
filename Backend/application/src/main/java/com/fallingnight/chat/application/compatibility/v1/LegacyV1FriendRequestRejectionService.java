package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Server-bound recipient authorization for the V1 reject command. */
public final class LegacyV1FriendRequestRejectionService
        implements LegacyV1FriendRequestRejectionUseCase {
    private final LegacyV1FriendRequestDecisionPort decisions;

    public LegacyV1FriendRequestRejectionService(LegacyV1FriendRequestDecisionPort decisions) {
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    @Override
    public LegacyV1FriendRequestRejectionResult reject(
            UUID accountId, long legacyRequestId) {
        Objects.requireNonNull(accountId, "accountId");
        if (legacyRequestId <= 0 || legacyRequestId > Integer.MAX_VALUE) {
            return LegacyV1FriendRequestRejectionResult.Rejected.INSTANCE;
        }
        return Objects.requireNonNull(
                decisions.reject(legacyRequestId, accountId), "decision result");
    }
}
