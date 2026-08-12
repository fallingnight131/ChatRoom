package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** V1-compatible acceptance result with internal notification routing context. */
public sealed interface LegacyV1FriendRequestAcceptanceResult {
    record Accepted(boolean duplicate, UUID requesterAccountId)
            implements LegacyV1FriendRequestAcceptanceResult {
        public Accepted {
            Objects.requireNonNull(requesterAccountId, "requesterAccountId");
        }
    }

    enum Rejected implements LegacyV1FriendRequestAcceptanceResult { INSTANCE }
}
