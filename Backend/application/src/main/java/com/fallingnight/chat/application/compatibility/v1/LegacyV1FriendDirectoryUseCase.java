package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

@FunctionalInterface
public interface LegacyV1FriendDirectoryUseCase {
    LegacyV1FriendDirectorySnapshot listFriends(UUID accountId);
}
