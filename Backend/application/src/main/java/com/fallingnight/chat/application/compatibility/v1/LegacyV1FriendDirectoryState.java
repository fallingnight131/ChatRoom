package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;

/** One complete authoritative friend/pending state read; never a partial page. */
public record LegacyV1FriendDirectoryState(
        List<LegacyV1FriendState> friends,
        int pendingFriendRequests) {
    public LegacyV1FriendDirectoryState {
        friends = List.copyOf(friends);
        if (pendingFriendRequests < 0) {
            throw new IllegalArgumentException("pendingFriendRequests");
        }
    }
}
