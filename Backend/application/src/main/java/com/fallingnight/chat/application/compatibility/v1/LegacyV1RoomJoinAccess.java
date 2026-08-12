package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.identity.StoredCredential;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authoritative room and membership state used before an atomic join attempt. */
public sealed interface LegacyV1RoomJoinAccess {
    record AlreadyMember(LegacyV1RoomJoinResult.Joined membership)
            implements LegacyV1RoomJoinAccess {
        public AlreadyMember {
            Objects.requireNonNull(membership, "membership");
            if (membership.newJoin()) {
                throw new IllegalArgumentException("existing V1 membership cannot be new");
            }
        }
    }

    record Candidate(UUID conversationId, long legacyRoomId, String roomName,
            UUID actorAccountId, Optional<StoredCredential> joinCredential)
            implements LegacyV1RoomJoinAccess {
        public Candidate {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(roomName, "roomName");
            Objects.requireNonNull(actorAccountId, "actorAccountId");
            joinCredential = Objects.requireNonNull(joinCredential, "joinCredential");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE || roomName.isBlank()
                    || joinCredential.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("V1 room join candidate");
            }
        }
    }

    enum Rejected implements LegacyV1RoomJoinAccess { NOT_FOUND, JOIN_DENIED }
}
