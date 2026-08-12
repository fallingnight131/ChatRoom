package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class LegacyV1FriendDirectoryServiceTest {
    private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PEER_A = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID PEER_B = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID DIRECT_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID DIRECT_B = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Test
    void composesCompleteLegacyIdentifiersPresenceUnreadAndPendingState() {
        LegacyV1FriendDirectoryService service = service(
                new LegacyV1FriendDirectoryState(List.of(
                        state(DIRECT_B, PEER_B, "zoe", "Zoe", 4, 17),
                        state(DIRECT_A, PEER_A, "alice", "Alice", 2, 9)), 3),
                Map.of(DIRECT_A, conversation(DIRECT_A, 10),
                        DIRECT_B, conversation(DIRECT_B, 20)),
                Map.of(PEER_A, new LegacyV1AccountIdentity(1, PEER_A),
                        PEER_B, new LegacyV1AccountIdentity(2, PEER_B)),
                Set.of(PEER_B));

        LegacyV1FriendDirectorySnapshot result = service.listFriends(OWNER);

        assertEquals(3, result.pendingFriendRequests());
        assertEquals(List.of(
                new LegacyV1FriendSummary(10, 1, "alice", "Alice", false, 2, 9),
                new LegacyV1FriendSummary(20, 2, "zoe", "Zoe", true, 4, 17)),
                result.friends());
    }

    @Test
    void preservesSelfChatAsOnlineWhenOwnerPresenceIsReturned() {
        LegacyV1FriendDirectoryService service = service(
                new LegacyV1FriendDirectoryState(
                        List.of(state(DIRECT_A, OWNER, "me", "Me", 0, 5)), 0),
                Map.of(DIRECT_A, conversation(DIRECT_A, 7)),
                Map.of(OWNER, new LegacyV1AccountIdentity(9, OWNER)),
                Set.of(OWNER));

        assertEquals(new LegacyV1FriendSummary(7, 9, "me", "Me", true, 0, 5),
                service.listFriends(OWNER).friends().getFirst());
    }

    @Test
    void rejectsIncompleteOrWrongKindProjectionInsteadOfReturningPruningList() {
        LegacyV1FriendDirectoryState state = new LegacyV1FriendDirectoryState(
                List.of(state(DIRECT_A, PEER_A, "alice", "Alice", 0, 0)), 0);
        assertThrows(IllegalStateException.class, () -> service(
                state, Map.of(), Map.of(PEER_A,
                        new LegacyV1AccountIdentity(1, PEER_A)), Set.of()).listFriends(OWNER));
        LegacyV1ConversationIdentity room = new LegacyV1ConversationIdentity(
                LegacyV1ConversationKind.ROOM, 10, DIRECT_A);
        assertThrows(IllegalStateException.class, () -> service(
                state, Map.of(DIRECT_A, room), Map.of(PEER_A,
                        new LegacyV1AccountIdentity(1, PEER_A)), Set.of()).listFriends(OWNER));
        assertThrows(IllegalStateException.class, () -> service(
                state, Map.of(DIRECT_A, conversation(DIRECT_A, 10)), Map.of(), Set.of())
                .listFriends(OWNER));
    }

    private static LegacyV1FriendState state(
            UUID conversation, UUID peer, String username, String display,
            long unread, long peerRead) {
        return new LegacyV1FriendState(
                conversation, peer, username, display, unread, peerRead);
    }

    private static LegacyV1ConversationIdentity conversation(UUID id, long legacyId) {
        return new LegacyV1ConversationIdentity(
                LegacyV1ConversationKind.FRIENDSHIP, legacyId, id);
    }

    private static LegacyV1FriendDirectoryService service(
            LegacyV1FriendDirectoryState state,
            Map<UUID, LegacyV1ConversationIdentity> conversations,
            Map<UUID, LegacyV1AccountIdentity> accounts,
            Set<UUID> online) {
        return new LegacyV1FriendDirectoryService(
                (accountId, maximum) -> {
                    assertEquals(OWNER, accountId);
                    assertEquals(LegacyV1FriendDirectoryService.MAX_FRIENDS, maximum);
                    return state;
                }, conversationProjection(conversations), accountProjection(accounts),
                requested -> {
                    if (!requested.containsAll(online)) throw new AssertionError();
                    return online;
                });
    }

    private static LegacyV1ConversationProjectionPort conversationProjection(
            Map<UUID, LegacyV1ConversationIdentity> values) {
        return new LegacyV1ConversationProjectionPort() {
            @Override public Optional<LegacyV1ConversationIdentity> findByLegacyId(
                    LegacyV1ConversationKind kind, long id) { return Optional.empty(); }
            @Override public Optional<LegacyV1ConversationIdentity> findByConversationId(UUID id) {
                return Optional.ofNullable(values.get(id));
            }
            @Override public Map<UUID, LegacyV1ConversationIdentity> findByConversationIds(
                    Set<UUID> ids) {
                Map<UUID, LegacyV1ConversationIdentity> result = new LinkedHashMap<>();
                ids.forEach(id -> Optional.ofNullable(values.get(id))
                        .ifPresent(value -> result.put(id, value)));
                return Map.copyOf(result);
            }
        };
    }

    private static LegacyV1AccountProjectionPort accountProjection(
            Map<UUID, LegacyV1AccountIdentity> values) {
        return new LegacyV1AccountProjectionPort() {
            @Override public Optional<LegacyV1AccountIdentity> findByPresentedUsername(
                    String username) { return Optional.empty(); }
            @Override public Optional<LegacyV1AccountIdentity> findByAccountId(UUID id) {
                return Optional.ofNullable(values.get(id));
            }
        };
    }
}
