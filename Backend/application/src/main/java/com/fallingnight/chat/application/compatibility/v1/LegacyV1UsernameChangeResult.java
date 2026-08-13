package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public sealed interface LegacyV1UsernameChangeResult {
    record RoomAudience(long legacyRoomId, Set<UUID> peerAccountIds) {
        public RoomAudience {
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE)
                throw new IllegalArgumentException("invalid legacy room ID");
            peerAccountIds = Set.copyOf(Objects.requireNonNull(
                    peerAccountIds, "peerAccountIds"));
            if (peerAccountIds.isEmpty() || peerAccountIds.stream().anyMatch(Objects::isNull))
                throw new IllegalArgumentException("invalid room peer audience");
        }
    }

    record Changed(UUID accountId, String oldUsername, String newUsername,
            boolean changed, Instant changedAt, Instant nextAllowedAt,
            List<RoomAudience> roomAudiences) implements LegacyV1UsernameChangeResult {
        public Changed {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(changedAt, "changedAt");
            Objects.requireNonNull(nextAllowedAt, "nextAllowedAt");
            roomAudiences = List.copyOf(Objects.requireNonNull(roomAudiences, "roomAudiences"));
            if (!LegacyV1UsernameChangeService.validStoredUsername(oldUsername)
                    || !LegacyV1UsernameChangeService.validUsername(newUsername)
                    || changed == oldUsername.equals(newUsername)
                    || nextAllowedAt.isBefore(changedAt)
                    || (!changed && !roomAudiences.isEmpty())
                    || roomAudiences.stream().anyMatch(room ->
                        room.peerAccountIds().contains(accountId))
                    || roomAudiences.stream().map(RoomAudience::legacyRoomId).distinct().count()
                        != roomAudiences.size())
                throw new IllegalArgumentException("invalid username change result");
        }
    }

    record Cooldown(Instant retryAt) implements LegacyV1UsernameChangeResult {
        public Cooldown { Objects.requireNonNull(retryAt, "retryAt"); }
    }

    enum Rejected implements LegacyV1UsernameChangeResult {
        INVALID_INPUT,
        SAME_AS_CURRENT,
        USERNAME_TAKEN,
        ACCOUNT_UNAVAILABLE
    }
}
