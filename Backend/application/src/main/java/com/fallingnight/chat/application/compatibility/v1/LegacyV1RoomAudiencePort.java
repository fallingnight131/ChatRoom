package com.fallingnight.chat.application.compatibility.v1;
import java.util.Set;
import java.util.UUID;
@FunctionalInterface public interface LegacyV1RoomAudiencePort {
    Set<UUID> activeMappedMembers(UUID conversationId, Set<UUID> candidateAccountIds);
}
