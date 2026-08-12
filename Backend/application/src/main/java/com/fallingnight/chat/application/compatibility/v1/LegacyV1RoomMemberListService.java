package com.fallingnight.chat.application.compatibility.v1;

import java.util.*;

/** Bounded authorized room directory with durable identity and presence separated. */
public final class LegacyV1RoomMemberListService implements LegacyV1RoomMemberListUseCase {
    public static final int MAX_MEMBERS = 1000;
    private final LegacyV1RoomMemberListPort members;
    private final LegacyV1PresencePort presence;
    public LegacyV1RoomMemberListService(
            LegacyV1RoomMemberListPort members, LegacyV1PresencePort presence) {
        this.members = Objects.requireNonNull(members, "members");
        this.presence = Objects.requireNonNull(presence, "presence");
    }

    @Override public LegacyV1RoomMemberListResult list(UUID actor, long roomId) {
        Objects.requireNonNull(actor, "actor");
        if (roomId <= 0 || roomId > Integer.MAX_VALUE) {
            return LegacyV1RoomMemberListResult.Rejected.INVALID_INPUT;
        }
        var query = Objects.requireNonNull(members.list(actor, roomId, MAX_MEMBERS + 1),
                "room member query result");
        if (query == LegacyV1RoomMemberListPort.QueryResult.Rejected.ROOM_ACCESS_DENIED) {
            return LegacyV1RoomMemberListResult.Rejected.ROOM_ACCESS_DENIED;
        }
        List<LegacyV1RoomMemberEntry> entries = ((LegacyV1RoomMemberListPort.QueryResult.Authorized)
                query).members();
        if (entries.size() > MAX_MEMBERS) {
            return LegacyV1RoomMemberListResult.Rejected.ROOM_TOO_LARGE;
        }
        Set<UUID> ids = new HashSet<>(); Set<String> usernames = new HashSet<>();
        for (var entry : entries) if (!ids.add(entry.accountId())
                || !usernames.add(entry.username())) {
            throw new IllegalStateException("V1 room member projection is inconsistent");
        }
        if (!ids.contains(actor)) {
            throw new IllegalStateException("V1 room member projection omitted actor");
        }
        Set<UUID> online = Set.copyOf(presence.onlineAccounts(Set.copyOf(ids)));
        if (!ids.containsAll(online)) {
            throw new IllegalStateException("V1 room member presence is inconsistent");
        }
        List<LegacyV1RoomMemberUser> users = entries.stream().map(entry ->
                new LegacyV1RoomMemberUser(entry.username(), entry.displayName(),
                        entry.role() != LegacyV1RoomMemberEntry.Role.MEMBER,
                        online.contains(entry.accountId()))).toList();
        return new LegacyV1RoomMemberListResult.Listed(roomId, users);
    }
}
