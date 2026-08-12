package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fallingnight.chat.application.conversation.ConversationDirectoryCursor;
import com.fallingnight.chat.application.conversation.ConversationDirectoryPage;
import com.fallingnight.chat.application.conversation.ConversationKind;
import com.fallingnight.chat.application.conversation.ConversationRole;
import com.fallingnight.chat.application.conversation.ConversationSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomDirectoryServiceTest {
    private static final UUID ACCOUNT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ROOM_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ROOM_B = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID DIRECT = UUID.fromString("20000000-0000-0000-0000-000000000003");

    @Test
    void projectsOnlyMappedGroupsWithAuthoritativeUnreadAndLegacyOrder() {
        List<Integer> pageLimits = new ArrayList<>();
        var first = page(List.of(
                summary(ROOM_B, ConversationKind.GROUP, "Room B", ConversationRole.MEMBER, 9, 4),
                summary(DIRECT, ConversationKind.DIRECT, "Peer", ConversationRole.MEMBER, 2, 2)), true);
        var second = page(List.of(
                summary(ROOM_A, ConversationKind.GROUP, "Room A", ConversationRole.OWNER, 7, 1)), false);
        var directory = new com.fallingnight.chat.application.conversation.ConversationDirectoryPort() {
            private int call;
            @Override
            public ConversationDirectoryPage list(
                    com.fallingnight.chat.application.conversation.ConversationDirectoryQuery query) {
                assertEquals(ACCOUNT, query.accountId());
                pageLimits.add(query.limit());
                return call++ == 0 ? first : second;
            }
        };
        Map<UUID, LegacyV1ConversationIdentity> mappings = Map.of(
                ROOM_A, mapping(ROOM_A, 1), ROOM_B, mapping(ROOM_B, 2));
        LegacyV1RoomDirectoryService service = new LegacyV1RoomDirectoryService(
                directory, projection(mappings));

        assertEquals(List.of(
                new LegacyV1RoomSummary(1, "Room A", 6, true),
                new LegacyV1RoomSummary(2, "Room B", 5, false)),
                service.listRooms(ACCOUNT));
        assertEquals(List.of(100, 100), pageLimits);
    }

    @Test
    void rejectsAnIncompleteMappingInsteadOfReturningAPartialPruningList() {
        LegacyV1RoomDirectoryService service = new LegacyV1RoomDirectoryService(
                query -> page(List.of(summary(
                        ROOM_A, ConversationKind.GROUP, "Room A",
                        ConversationRole.MEMBER, 1, 0)), false),
                projection(Map.of()));
        assertThrows(IllegalStateException.class, () -> service.listRooms(ACCOUNT));
    }

    private static ConversationDirectoryPage page(
            List<ConversationSummary> rows, boolean hasMore) {
        Optional<ConversationDirectoryCursor> next = rows.isEmpty()
                ? Optional.empty()
                : Optional.of(new ConversationDirectoryCursor(
                        rows.getLast().updatedAt(), rows.getLast().conversationId()));
        return new ConversationDirectoryPage(rows, next, hasMore);
    }

    private static ConversationSummary summary(
            UUID id, ConversationKind kind, String name, ConversationRole role,
            long latest, long read) {
        return new ConversationSummary(
                id, kind, name, role, latest, read,
                Instant.parse("2026-08-13T00:00:00Z").plusSeconds(id.getLeastSignificantBits()));
    }

    private static LegacyV1ConversationIdentity mapping(UUID id, long legacyId) {
        return new LegacyV1ConversationIdentity(LegacyV1ConversationKind.ROOM, legacyId, id);
    }

    private static LegacyV1ConversationProjectionPort projection(
            Map<UUID, LegacyV1ConversationIdentity> values) {
        return new LegacyV1ConversationProjectionPort() {
            @Override
            public Optional<LegacyV1ConversationIdentity> findByLegacyId(
                    LegacyV1ConversationKind kind, long legacyConversationId) {
                return values.values().stream().filter(value -> value.legacyKind() == kind
                        && value.legacyConversationId() == legacyConversationId).findFirst();
            }

            @Override
            public Optional<LegacyV1ConversationIdentity> findByConversationId(UUID id) {
                return Optional.ofNullable(values.get(id));
            }

            @Override
            public Map<UUID, LegacyV1ConversationIdentity> findByConversationIds(Set<UUID> ids) {
                Map<UUID, LegacyV1ConversationIdentity> selected = new LinkedHashMap<>();
                ids.forEach(id -> Optional.ofNullable(values.get(id))
                        .ifPresent(value -> selected.put(id, value)));
                return Map.copyOf(selected);
            }
        };
    }
}
