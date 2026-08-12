package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

@FunctionalInterface
public interface LegacyV1FriendRequestAcceptancePort {
    LegacyV1FriendRequestAcceptanceResult accept(
            long legacyRequestId, UUID recipientAccountId);
}
