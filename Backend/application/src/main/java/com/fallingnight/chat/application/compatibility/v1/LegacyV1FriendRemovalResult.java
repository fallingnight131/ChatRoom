package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1FriendRemovalResult {
    record Removed(boolean duplicate, UUID targetAccountId, String targetUsername)
            implements LegacyV1FriendRemovalResult {
        public Removed {
            Objects.requireNonNull(targetAccountId, "targetAccountId");
            Objects.requireNonNull(targetUsername, "targetUsername");
        }
    }

    enum Rejected implements LegacyV1FriendRemovalResult {
        TARGET_NOT_FOUND,
        SELF_REMOVAL,
        NOT_FRIENDS,
        INVALID_TARGET
    }
}
