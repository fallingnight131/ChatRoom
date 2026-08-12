package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;
import java.util.Set;

/** Immutable source facts needed for V1 pending-request planning. */
public record V1ContactRequestSourceSnapshot(
        Set<Long> legacyUserIds,
        List<V1ExistingFriendPair> friendships,
        List<V1ContactRequestRow> requests) {
    public V1ContactRequestSourceSnapshot {
        legacyUserIds = Set.copyOf(legacyUserIds);
        friendships = List.copyOf(friendships);
        requests = List.copyOf(requests);
    }
}
