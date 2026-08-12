package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomMemberListServiceTest {
    @Test void bindsActorBoundsQueryAndJoinsPresenceWithoutUuidProjection() {
        UUID actor = UUID.randomUUID(), peer = UUID.randomUUID();
        var service = new LegacyV1RoomMemberListService((actual, room, limit) -> {
            assertEquals(actor, actual); assertEquals(77, room); assertEquals(1001, limit);
            return new LegacyV1RoomMemberListPort.QueryResult.Authorized(List.of(
                    entry(actor, "owner", LegacyV1RoomMemberEntry.Role.OWNER),
                    entry(peer, "peer", LegacyV1RoomMemberEntry.Role.MEMBER)));
        }, ids -> { assertEquals(Set.of(actor, peer), ids); return Set.of(peer); });

        var listed = (LegacyV1RoomMemberListResult.Listed) service.list(actor, 77);
        assertEquals(77, listed.legacyRoomId()); assertEquals(2, listed.users().size());
        assertTrue(listed.users().getFirst().admin());
        assertFalse(listed.users().getFirst().online());
        assertTrue(listed.users().getLast().online());
    }

    @Test void rejectsInvalidUnauthorizedAndOverflowWithoutPresence() {
        UUID actor = UUID.randomUUID();
        var invalid = new LegacyV1RoomMemberListService((a, r, l) -> fail(), ids -> fail());
        assertEquals(LegacyV1RoomMemberListResult.Rejected.INVALID_INPUT,
                invalid.list(actor, 0));
        var denied = new LegacyV1RoomMemberListService((a, r, l) ->
                LegacyV1RoomMemberListPort.QueryResult.Rejected.ROOM_ACCESS_DENIED,
                ids -> fail());
        assertEquals(LegacyV1RoomMemberListResult.Rejected.ROOM_ACCESS_DENIED,
                denied.list(actor, 77));
        List<LegacyV1RoomMemberEntry> overflow = new ArrayList<>();
        for (int index = 0; index <= 1000; index++) overflow.add(entry(
                index == 0 ? actor : UUID.randomUUID(), "u" + index,
                LegacyV1RoomMemberEntry.Role.MEMBER));
        var excessive = new LegacyV1RoomMemberListService((a, r, l) ->
                new LegacyV1RoomMemberListPort.QueryResult.Authorized(overflow), ids -> fail());
        assertEquals(LegacyV1RoomMemberListResult.Rejected.ROOM_TOO_LARGE,
                excessive.list(actor, 77));
    }

    @Test void failsClosedOnMissingActorDuplicateOrForeignPresence() {
        UUID actor = UUID.randomUUID(), peer = UUID.randomUUID();
        var missing = service(List.of(entry(peer, "peer",
                LegacyV1RoomMemberEntry.Role.MEMBER)), Set.of());
        assertThrows(IllegalStateException.class, () -> missing.list(actor, 77));
        var duplicate = service(List.of(
                entry(actor, "same", LegacyV1RoomMemberEntry.Role.OWNER),
                entry(peer, "same", LegacyV1RoomMemberEntry.Role.MEMBER)), Set.of());
        assertThrows(IllegalStateException.class, () -> duplicate.list(actor, 77));
        var foreign = service(List.of(entry(actor, "owner",
                LegacyV1RoomMemberEntry.Role.OWNER)), Set.of(peer));
        assertThrows(IllegalStateException.class, () -> foreign.list(actor, 77));
    }

    private static LegacyV1RoomMemberListService service(
            List<LegacyV1RoomMemberEntry> entries, Set<UUID> online) {
        return new LegacyV1RoomMemberListService((a, r, l) ->
                new LegacyV1RoomMemberListPort.QueryResult.Authorized(entries), ids -> online);
    }
    private static LegacyV1RoomMemberEntry entry(
            UUID id, String username, LegacyV1RoomMemberEntry.Role role) {
        return new LegacyV1RoomMemberEntry(id, username, username + " display", role);
    }
}
