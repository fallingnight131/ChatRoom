package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public sealed interface LegacyV1RoomLeaveResult {
    record OwnershipTransfer(UUID successorAccountId, String successorDisplayName) {
        public OwnershipTransfer {
            Objects.requireNonNull(successorAccountId, "successorAccountId");
            Objects.requireNonNull(successorDisplayName, "successorDisplayName");
            if (successorDisplayName.isBlank()
                    || successorDisplayName.codePointCount(0, successorDisplayName.length()) > 100
                    || successorDisplayName.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("V1 room ownership successor display name");
            }
        }
    }

    record Left(UUID conversationId, long legacyRoomId, UUID actorAccountId,
            boolean newLeave, boolean dissolved,
            Optional<OwnershipTransfer> ownershipTransfer) implements LegacyV1RoomLeaveResult {
        public Left {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(actorAccountId, "actorAccountId");
            ownershipTransfer = Objects.requireNonNull(
                    ownershipTransfer, "ownershipTransfer");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE
                    || (!newLeave && ownershipTransfer.isPresent())
                    || (dissolved && ownershipTransfer.isPresent())
                    || ownershipTransfer.stream().anyMatch(
                            transfer -> transfer.successorAccountId().equals(actorAccountId))) {
                throw new IllegalArgumentException("V1 room leave result");
            }
        }
    }

    enum Rejected implements LegacyV1RoomLeaveResult {
        INVALID_INPUT,
        NOT_FOUND,
        NOT_MEMBER,
        LEAVE_DENIED
    }
}
