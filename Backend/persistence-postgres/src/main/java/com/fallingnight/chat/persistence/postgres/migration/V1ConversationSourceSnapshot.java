package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;
import java.util.Set;

/** Immutable minimal V1 conversation graph read before any target comparison. */
public record V1ConversationSourceSnapshot(
        Set<Long> legacyUserIds,
        List<V1RoomRow> rooms,
        List<V1RoomMembershipRow> roomMemberships,
        Set<V1RoomAdministrator> roomAdministrators,
        List<V1FriendshipRow> friendships) {
    public V1ConversationSourceSnapshot {
        legacyUserIds = Set.copyOf(legacyUserIds);
        rooms = List.copyOf(rooms);
        roomMemberships = List.copyOf(roomMemberships);
        roomAdministrators = Set.copyOf(roomAdministrators);
        friendships = List.copyOf(friendships);
    }
}
