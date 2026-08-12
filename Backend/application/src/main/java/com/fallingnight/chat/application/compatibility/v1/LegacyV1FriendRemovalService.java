package com.fallingnight.chat.application.compatibility.v1;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Server-bound V1 friend removal with bounded exact username input. */
public final class LegacyV1FriendRemovalService implements LegacyV1FriendRemovalUseCase {
    public static final int MAX_USERNAME_UTF8_BYTES = 128;
    private final LegacyV1FriendRemovalPort removals;

    public LegacyV1FriendRemovalService(LegacyV1FriendRemovalPort removals) {
        this.removals = Objects.requireNonNull(removals, "removals");
    }

    @Override
    public LegacyV1FriendRemovalResult remove(
            UUID actorAccountId, String targetUsername) {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        if (targetUsername == null
                || targetUsername.isBlank()
                || !targetUsername.equals(targetUsername.strip())
                || targetUsername.getBytes(StandardCharsets.UTF_8).length
                        > MAX_USERNAME_UTF8_BYTES
                || targetUsername.codePoints().anyMatch(Character::isISOControl)) {
            return LegacyV1FriendRemovalResult.Rejected.INVALID_TARGET;
        }
        LegacyV1FriendRemovalResult result = Objects.requireNonNull(
                removals.remove(actorAccountId, targetUsername), "removal result");
        if (result instanceof LegacyV1FriendRemovalResult.Removed removed
                && removed.targetAccountId().equals(actorAccountId)) {
            throw new IllegalStateException("friend removal target equals actor");
        }
        return result;
    }
}
