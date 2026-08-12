package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

@FunctionalInterface
public interface LegacyV1FriendRemovalPort {
    LegacyV1FriendRemovalResult remove(UUID actorAccountId, String targetUsername);
}
