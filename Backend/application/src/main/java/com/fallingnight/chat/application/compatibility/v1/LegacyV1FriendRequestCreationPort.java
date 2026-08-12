package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

@FunctionalInterface
public interface LegacyV1FriendRequestCreationPort {
    LegacyV1FriendRequestCreationResult create(UUID requesterAccountId, String targetUsername);
}
