package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V1ConversationImportPlannerTest {
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void plansCanonicalRoomsRolesAndDirectPairsDeterministically() {
        V1ConversationSourceSnapshot source = new V1ConversationSourceSnapshot(
                Set.of(1L, 2L, 3L),
                List.of(new V1RoomRow(9, "Project Room", 2, 73, CREATED)),
                List.of(
                        new V1RoomMembershipRow(9, 3, CREATED.plusSeconds(2), 11),
                        new V1RoomMembershipRow(9, 1, CREATED.plusSeconds(1), 0),
                        new V1RoomMembershipRow(9, 2, CREATED, 7)),
                Set.of(new V1RoomAdministrator(9, 3)),
                List.of(new V1FriendshipRow(4, 2, 1, CREATED, 5, 6)));

        V1ConversationImportPlan first = new V1ConversationImportPlanner().plan(source);
        V1ConversationImportPlan reordered = new V1ConversationImportPlanner().plan(
                new V1ConversationSourceSnapshot(
                        source.legacyUserIds(),
                        source.rooms(),
                        source.roomMemberships().reversed(),
                        source.roomAdministrators(),
                        source.friendships()));

        assertTrue(first.readyToCompareWithTarget());
        assertEquals(first, reordered);
        assertEquals(2, first.conversations().size());
        assertEquals(5, first.memberships().size());
        assertEquals(64, first.sourceFingerprintSha256().length());
        assertEquals(73, first.conversations().stream()
                .filter(value -> value.legacyKind() == LegacyV1ConversationKind.ROOM)
                .findFirst().orElseThrow().maxMembers());
        V1ConversationImportPlan changedLimit = new V1ConversationImportPlanner().plan(
                new V1ConversationSourceSnapshot(source.legacyUserIds(),
                        List.of(new V1RoomRow(9, "Project Room", 2, 74, CREATED)),
                        source.roomMemberships(), source.roomAdministrators(),
                        source.friendships()));
        assertNotEquals(first.sourceFingerprintSha256(),
                changedLimit.sourceFingerprintSha256());

        PlannedV1Conversation friendship = first.conversations().stream()
                .filter(value -> value.legacyKind() == LegacyV1ConversationKind.FRIENDSHIP)
                .findFirst().orElseThrow();
        assertTrue(friendship.firstAccountId().toString()
                .compareTo(friendship.secondAccountId().toString()) < 0);
        assertEquals(5, friendship.conversationId().version());
        assertNotEquals(
                V1ConversationImportPlanner.deterministicRoomId(4),
                friendship.conversationId());

        var roles = first.memberships().stream()
                .filter(value -> value.conversationId().equals(
                        V1ConversationImportPlanner.deterministicRoomId(9)))
                .map(PlannedV1ConversationMember::role)
                .sorted()
                .toList();
        assertEquals(List.of("ADMIN", "MEMBER", "OWNER"), roles);
        assertTrue(first.memberships().stream().anyMatch(
                value -> value.legacyLastReadMessageId() == 11));
    }

    @Test
    void preservesSelfFriendshipWhileBlockingInvalidRoomGraphWithoutLeakingNames() {
        V1ConversationSourceSnapshot source = new V1ConversationSourceSnapshot(
                Set.of(1L, 2L),
                List.of(new V1RoomRow(3, "private-room-name", 1, 0, CREATED)),
                List.of(
                        new V1RoomMembershipRow(3, 2, CREATED, -1),
                        new V1RoomMembershipRow(99, 1, CREATED, 0)),
                Set.of(new V1RoomAdministrator(3, 7)),
                List.of(new V1FriendshipRow(8, 1, 1, CREATED, 0, 0)));

        V1ConversationImportPlan plan = new V1ConversationImportPlanner().plan(source);

        assertFalse(plan.readyToCompareWithTarget());
        Set<String> codes = plan.issues().stream()
                .map(V1ConversationImportIssue::code)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.contains("CREATOR_NOT_ROOM_MEMBER"));
        assertTrue(codes.contains("INVALID_ROOM_READ_POINTER"));
        assertTrue(codes.contains("DANGLING_ROOM_MEMBERSHIP"));
        assertTrue(codes.contains("DANGLING_ROOM_ADMIN"));
        assertTrue(codes.contains("INVALID_ROOM_MEMBER_LIMIT"));
        assertFalse(codes.contains("SELF_FRIENDSHIP_UNSUPPORTED"));
        assertFalse(plan.issues().toString().contains("private-room-name"));
        assertEquals(1, plan.conversations().size());
        PlannedV1Conversation self = plan.conversations().getFirst();
        assertEquals(self.firstAccountId(), self.secondAccountId());
        assertEquals(1, plan.memberships().size());
    }

    @Test
    void blocksDuplicateFriendPairAndUnknownParticipants() {
        V1ConversationSourceSnapshot source = new V1ConversationSourceSnapshot(
                Set.of(1L, 2L),
                List.of(),
                List.of(),
                Set.of(),
                List.of(
                        new V1FriendshipRow(1, 1, 2, CREATED, 0, 0),
                        new V1FriendshipRow(2, 2, 1, CREATED, 0, 0),
                        new V1FriendshipRow(3, 1, 9, CREATED, 0, 0)));

        V1ConversationImportPlan plan = new V1ConversationImportPlanner().plan(source);

        assertFalse(plan.readyToCompareWithTarget());
        assertTrue(plan.issues().stream().anyMatch(
                issue -> "DUPLICATE_FRIENDSHIP_PAIR".equals(issue.code())));
        assertTrue(plan.issues().stream().anyMatch(
                issue -> "UNKNOWN_FRIENDSHIP_USER".equals(issue.code())));
        assertEquals(1, plan.conversations().size());
    }
}
