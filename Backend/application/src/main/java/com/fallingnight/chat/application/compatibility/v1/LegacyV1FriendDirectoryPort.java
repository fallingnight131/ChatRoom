package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

/** Reads the complete bounded friend directory for one server-bound account. */
@FunctionalInterface
public interface LegacyV1FriendDirectoryPort {
    LegacyV1FriendDirectoryState read(UUID accountId, int maximumFriends);
}
