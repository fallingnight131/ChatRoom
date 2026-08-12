package com.fallingnight.chat.application.compatibility.v1;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Complete bounded pending-request use case for the V1 compatibility boundary. */
public final class LegacyV1PendingFriendRequestService
        implements LegacyV1PendingFriendRequestUseCase {
    public static final int MAX_PENDING_REQUESTS = 1_000;

    private final LegacyV1PendingFriendRequestPort requests;

    public LegacyV1PendingFriendRequestService(LegacyV1PendingFriendRequestPort requests) {
        this.requests = Objects.requireNonNull(requests, "requests");
    }

    @Override
    public List<LegacyV1PendingFriendRequest> listPending(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        List<LegacyV1PendingFriendRequest> result = List.copyOf(
                requests.listIncoming(accountId, MAX_PENDING_REQUESTS));
        if (result.size() > MAX_PENDING_REQUESTS) {
            throw new IllegalStateException("V1 pending requests exceed the fixed bound");
        }
        Set<Long> ids = new HashSet<>();
        for (LegacyV1PendingFriendRequest request : result) {
            if (!ids.add(request.requestId())) {
                throw new IllegalStateException("V1 pending request identifier is duplicated");
            }
        }
        return result;
    }
}
