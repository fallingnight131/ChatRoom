package com.fallingnight.chat.application.compatibility.v1;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded authenticated V1 room search without canonical identity exposure. */
public final class LegacyV1RoomSearchService implements LegacyV1RoomSearchUseCase {
    public static final int MAX_RESULTS = 20;
    public static final int MAX_KEYWORD_UTF8_BYTES = 256;
    private final LegacyV1RoomSearchPort rooms;
    public LegacyV1RoomSearchService(LegacyV1RoomSearchPort rooms) {
        this.rooms = Objects.requireNonNull(rooms, "rooms");
    }

    @Override public LegacyV1RoomSearchResult search(UUID actorAccountId, String keyword) {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        if (keyword == null) return LegacyV1RoomSearchResult.Rejected.INSTANCE;
        String normalized = keyword.strip();
        if (normalized.isEmpty()
                || normalized.getBytes(StandardCharsets.UTF_8).length > MAX_KEYWORD_UTF8_BYTES
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            return LegacyV1RoomSearchResult.Rejected.INSTANCE;
        }
        List<LegacyV1RoomSearchEntry> entries = List.copyOf(Objects.requireNonNull(
                rooms.search(actorAccountId, normalized, MAX_RESULTS), "room search result"));
        if (entries.size() > MAX_RESULTS) {
            throw new IllegalStateException("V1 room search exceeded its result bound");
        }
        Set<UUID> conversations = new HashSet<>();
        Set<Long> legacyIds = new HashSet<>();
        for (LegacyV1RoomSearchEntry entry : entries) {
            if (!conversations.add(entry.conversationId())
                    || !legacyIds.add(entry.legacyRoomId())) {
                throw new IllegalStateException("V1 room search projection is inconsistent");
            }
        }
        return new LegacyV1RoomSearchResult.Found(entries.stream()
                .map(entry -> new LegacyV1RoomSearchRoom(entry.legacyRoomId(),
                        entry.roomName(), entry.legacyCreatorId(), entry.memberCount()))
                .toList());
    }
}
