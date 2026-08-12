package com.fallingnight.chat.application.compatibility.v1;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
/** Authoritative batch filter for process-local V1 room fan-out candidates. */
public final class LegacyV1RoomAudienceService {
    public static final int MAX_CANDIDATES = 10_000;
    private final LegacyV1RoomAudiencePort audience;
    public LegacyV1RoomAudienceService(LegacyV1RoomAudiencePort audience) {
        this.audience = Objects.requireNonNull(audience, "audience");
    }
    public Set<UUID> activeMappedMembers(UUID conversationId, Set<UUID> candidates) {
        Objects.requireNonNull(conversationId, "conversationId");
        candidates = Set.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (candidates.size() > MAX_CANDIDATES)
            throw new IllegalArgumentException("room audience candidates");
        if (candidates.isEmpty()) return Set.of();
        Set<UUID> result = Set.copyOf(Objects.requireNonNull(
                audience.activeMappedMembers(conversationId, candidates), "room audience"));
        if (!candidates.containsAll(result))
            throw new IllegalStateException("room audience added a non-candidate");
        return result;
    }
}
