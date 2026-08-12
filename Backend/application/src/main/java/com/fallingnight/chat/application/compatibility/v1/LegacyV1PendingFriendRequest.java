package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.Objects;

/** Exact pending-request fields consumed by supported V1 clients. */
public record LegacyV1PendingFriendRequest(
        long requestId,
        long fromUserId,
        String fromUsername,
        String fromDisplayName,
        Instant createdAt) {
    public LegacyV1PendingFriendRequest {
        if (requestId <= 0 || requestId > Integer.MAX_VALUE
                || fromUserId <= 0 || fromUserId > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("V1 pending identifiers must fit signed integers");
        }
        Objects.requireNonNull(fromUsername, "fromUsername");
        Objects.requireNonNull(fromDisplayName, "fromDisplayName");
        Objects.requireNonNull(createdAt, "createdAt");
        if (fromUsername.isBlank() || fromUsername.length() > 128
                || fromDisplayName.isBlank() || fromDisplayName.length() > 100) {
            throw new IllegalArgumentException("V1 pending identity is outside bounds");
        }
    }
}
