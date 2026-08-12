package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

@FunctionalInterface
public interface LegacyV1FriendRequestRejectionUseCase {
    LegacyV1FriendRequestRejectionResult reject(UUID accountId, long legacyRequestId);
}
