package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

@FunctionalInterface
public interface LegacyV1FriendRequestAcceptanceUseCase {
    LegacyV1FriendRequestAcceptanceResult accept(UUID accountId, long legacyRequestId);
}
