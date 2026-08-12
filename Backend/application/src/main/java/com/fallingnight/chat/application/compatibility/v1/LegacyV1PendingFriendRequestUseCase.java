package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface LegacyV1PendingFriendRequestUseCase {
    List<LegacyV1PendingFriendRequest> listPending(UUID accountId);
}
