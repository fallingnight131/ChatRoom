package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

@FunctionalInterface
public interface LegacyV1FriendRequestDecisionPort {
    LegacyV1FriendRequestRejectionResult reject(long legacyRequestId, UUID recipientAccountId);
}
