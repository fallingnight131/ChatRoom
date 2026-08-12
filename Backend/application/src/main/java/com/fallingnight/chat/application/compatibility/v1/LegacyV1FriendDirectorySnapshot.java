package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;

/** Complete V1 response model; clients may safely treat it as authoritative. */
public record LegacyV1FriendDirectorySnapshot(
        List<LegacyV1FriendSummary> friends,
        int pendingFriendRequests) {
    public LegacyV1FriendDirectorySnapshot {
        friends = List.copyOf(friends);
        if (pendingFriendRequests < 0) {
            throw new IllegalArgumentException("pendingFriendRequests");
        }
    }
}
