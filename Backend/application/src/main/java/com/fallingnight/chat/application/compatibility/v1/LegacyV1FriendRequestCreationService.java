package com.fallingnight.chat.application.compatibility.v1;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Server-bound V1 friend-request creation with bounded exact username input. */
public final class LegacyV1FriendRequestCreationService
        implements LegacyV1FriendRequestCreationUseCase {
    public static final int MAX_USERNAME_UTF8_BYTES = 128;
    private final LegacyV1FriendRequestCreationPort creation;

    public LegacyV1FriendRequestCreationService(LegacyV1FriendRequestCreationPort creation) {
        this.creation = Objects.requireNonNull(creation, "creation");
    }

    @Override
    public LegacyV1FriendRequestCreationResult create(
            UUID requesterAccountId, String targetUsername) {
        Objects.requireNonNull(requesterAccountId, "requesterAccountId");
        if (targetUsername == null
                || targetUsername.isBlank()
                || !targetUsername.equals(targetUsername.strip())
                || targetUsername.getBytes(StandardCharsets.UTF_8).length
                        > MAX_USERNAME_UTF8_BYTES
                || targetUsername.codePoints().anyMatch(Character::isISOControl)) {
            return LegacyV1FriendRequestCreationResult.Rejected.INVALID_TARGET;
        }
        LegacyV1FriendRequestCreationResult result = Objects.requireNonNull(
                creation.create(requesterAccountId, targetUsername), "creation result");
        if (result instanceof LegacyV1FriendRequestCreationResult.Accepted accepted
                && accepted.recipientAccountId().equals(requesterAccountId)) {
            throw new IllegalStateException("friend request recipient equals requester");
        }
        return result;
    }
}
