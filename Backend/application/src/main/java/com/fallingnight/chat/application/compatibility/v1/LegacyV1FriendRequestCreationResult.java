package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1FriendRequestCreationResult {
    record Accepted(boolean duplicate, UUID recipientAccountId)
            implements LegacyV1FriendRequestCreationResult {
        public Accepted {
            Objects.requireNonNull(recipientAccountId, "recipientAccountId");
        }
    }

    enum Rejected implements LegacyV1FriendRequestCreationResult {
        USER_NOT_FOUND,
        SELF_REQUEST,
        ALREADY_FRIENDS,
        REVERSE_PENDING,
        INVALID_TARGET
    }
}
