package com.fallingnight.chat.application.compatibility.v1;

/** Generic V1-compatible result; only accepted distinguishes first apply internally. */
public sealed interface LegacyV1FriendRequestRejectionResult {
    record Accepted(boolean duplicate) implements LegacyV1FriendRequestRejectionResult { }
    enum Rejected implements LegacyV1FriendRequestRejectionResult { INSTANCE }
}
