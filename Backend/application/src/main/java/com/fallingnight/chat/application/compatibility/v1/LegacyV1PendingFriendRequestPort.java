package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface LegacyV1PendingFriendRequestPort {
    List<LegacyV1PendingFriendRequest> listIncoming(UUID recipientAccountId, int maximumRows);
}
