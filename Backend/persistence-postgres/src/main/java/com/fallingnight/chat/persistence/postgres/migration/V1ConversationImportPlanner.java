package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure deterministic validation and mapping for V1 room/friendship metadata. */
public final class V1ConversationImportPlanner {
    private static final UUID ROOM_NAMESPACE =
            UUID.fromString("b372a8d4-c0f6-5de3-997b-d172ee0dc001");
    private static final UUID FRIENDSHIP_NAMESPACE =
            UUID.fromString("e6b16f32-ef32-5c4c-9943-6cc52b24d002");

    public V1ConversationImportPlan plan(V1ConversationSourceSnapshot source) {
        List<V1RoomRow> rooms = sortedRooms(source.rooms());
        List<V1RoomMembershipRow> memberships = sortedMemberships(source.roomMemberships());
        List<V1FriendshipRow> friendships = sortedFriendships(source.friendships());
        Set<Long> users = source.legacyUserIds();
        Set<V1RoomAdministrator> administrators = source.roomAdministrators();
        List<V1ConversationImportIssue> issues = new ArrayList<>();
        List<PlannedV1Conversation> planned = new ArrayList<>();
        List<PlannedV1ConversationMember> plannedMembers = new ArrayList<>();

        Map<Long, List<V1RoomMembershipRow>> membersByRoom = new HashMap<>();
        for (V1RoomMembershipRow membership : memberships) {
            membersByRoom.computeIfAbsent(membership.legacyRoomId(), ignored -> new ArrayList<>())
                    .add(membership);
        }
        Set<Long> roomIds = new HashSet<>();
        Set<V1RoomAdministrator> consumedAdministrators = new HashSet<>();
        for (V1RoomRow room : rooms) {
            List<V1ConversationImportIssue> roomIssues = new ArrayList<>();
            validateRoom(room, users, roomIds, membersByRoom.getOrDefault(
                    room.legacyRoomId(), List.of()), administrators,
                    consumedAdministrators, roomIssues);
            issues.addAll(roomIssues);
            if (!roomIssues.isEmpty()) {
                continue;
            }
            UUID conversationId = deterministicRoomId(room.legacyRoomId());
            planned.add(new PlannedV1Conversation(
                    LegacyV1ConversationKind.ROOM,
                    room.legacyRoomId(),
                    conversationId,
                    room.name(),
                    room.maxMembers(),
                    null,
                    null,
                    room.createdAt()));
            for (V1RoomMembershipRow member : membersByRoom.get(room.legacyRoomId())) {
                String role = member.legacyUserId() == room.creatorUserId()
                        ? "OWNER"
                        : administrators.contains(new V1RoomAdministrator(
                                room.legacyRoomId(), member.legacyUserId()))
                                ? "ADMIN" : "MEMBER";
                plannedMembers.add(new PlannedV1ConversationMember(
                        conversationId,
                        V1IdentityImportPlanner.deterministicUserId(member.legacyUserId()),
                        role,
                        member.joinedAt(),
                        member.legacyLastReadMessageId()));
            }
        }
        for (V1RoomAdministrator administrator : administrators) {
            if (!consumedAdministrators.contains(administrator)) {
                issues.add(issue(LegacyV1ConversationKind.ROOM,
                        administrator.legacyRoomId(), "DANGLING_ROOM_ADMIN",
                        "room administrator must reference an imported membership"));
            }
        }
        for (Long roomId : membersByRoom.keySet()) {
            if (!roomIds.contains(roomId)) {
                issues.add(issue(LegacyV1ConversationKind.ROOM, roomId,
                        "DANGLING_ROOM_MEMBERSHIP",
                        "room membership must reference an imported room"));
            }
        }

        Set<Long> friendshipIds = new HashSet<>();
        Set<String> friendshipPairs = new HashSet<>();
        for (V1FriendshipRow friendship : friendships) {
            List<V1ConversationImportIssue> friendshipIssues = validateFriendship(
                    friendship, users, friendshipIds, friendshipPairs);
            issues.addAll(friendshipIssues);
            if (!friendshipIssues.isEmpty()) {
                continue;
            }
            UUID first = V1IdentityImportPlanner.deterministicUserId(friendship.firstUserId());
            UUID second = V1IdentityImportPlanner.deterministicUserId(friendship.secondUserId());
            UUID canonicalFirst = first.toString().compareTo(second.toString()) < 0
                    ? first : second;
            UUID canonicalSecond = canonicalFirst.equals(first) ? second : first;
            UUID conversationId = deterministicFriendshipId(friendship.legacyFriendshipId());
            planned.add(new PlannedV1Conversation(
                    LegacyV1ConversationKind.FRIENDSHIP,
                    friendship.legacyFriendshipId(),
                    conversationId,
                    null,
                    null,
                    canonicalFirst,
                    canonicalSecond,
                    friendship.createdAt()));
            plannedMembers.add(new PlannedV1ConversationMember(
                    conversationId,
                    first,
                    "MEMBER",
                    friendship.createdAt(),
                    friendship.firstLastReadMessageId()));
            if (!first.equals(second)) {
                plannedMembers.add(new PlannedV1ConversationMember(
                        conversationId,
                        second,
                        "MEMBER",
                        friendship.createdAt(),
                        friendship.secondLastReadMessageId()));
            }
        }

        planned.sort(Comparator.comparing(PlannedV1Conversation::legacyKind)
                .thenComparingLong(PlannedV1Conversation::legacyId));
        plannedMembers.sort(Comparator.comparing(PlannedV1ConversationMember::conversationId)
                .thenComparing(PlannedV1ConversationMember::accountId));
        return new V1ConversationImportPlan(
                fingerprint(source, rooms, memberships, friendships),
                rooms.size(),
                friendships.size(),
                planned,
                plannedMembers,
                issues);
    }

    public static UUID deterministicRoomId(long legacyRoomId) {
        return deterministicId(ROOM_NAMESPACE, "v1-room:", legacyRoomId);
    }

    public static UUID deterministicFriendshipId(long legacyFriendshipId) {
        return deterministicId(FRIENDSHIP_NAMESPACE, "v1-friendship:", legacyFriendshipId);
    }

    private static void validateRoom(
            V1RoomRow room,
            Set<Long> users,
            Set<Long> roomIds,
            List<V1RoomMembershipRow> members,
            Set<V1RoomAdministrator> administrators,
            Set<V1RoomAdministrator> consumedAdministrators,
            List<V1ConversationImportIssue> issues) {
        long id = room.legacyRoomId();
        if (id <= 0) {
            issues.add(issue(LegacyV1ConversationKind.ROOM, id,
                    "INVALID_ROOM_ID", "legacy room id must be positive"));
        } else if (!roomIds.add(id)) {
            issues.add(issue(LegacyV1ConversationKind.ROOM, id,
                    "DUPLICATE_ROOM_ID", "legacy room id is duplicated"));
        }
        if (!boundedCharacters(room.name(), 1, 100)) {
            issues.add(issue(LegacyV1ConversationKind.ROOM, id,
                    "INVALID_ROOM_NAME", "room name must contain 1..100 characters"));
        }
        if (!users.contains(room.creatorUserId())) {
            issues.add(issue(LegacyV1ConversationKind.ROOM, id,
                    "UNKNOWN_ROOM_CREATOR", "room creator must reference an imported user"));
        }
        if (room.createdAt() == null) {
            issues.add(issue(LegacyV1ConversationKind.ROOM, id,
                    "INVALID_ROOM_CREATED_AT", "room creation timestamp is required"));
        }
        if (room.maxMembers() < 1 || room.maxMembers() > 1_000_000) {
            issues.add(issue(LegacyV1ConversationKind.ROOM, id,
                    "INVALID_ROOM_MEMBER_LIMIT",
                    "room member limit must be between 1 and 1000000"));
        }
        Set<Long> memberIds = new HashSet<>();
        for (V1RoomMembershipRow member : members) {
            if (!memberIds.add(member.legacyUserId())) {
                issues.add(issue(LegacyV1ConversationKind.ROOM, id,
                        "DUPLICATE_ROOM_MEMBER", "room member is duplicated"));
            }
            if (!users.contains(member.legacyUserId())) {
                issues.add(issue(LegacyV1ConversationKind.ROOM, id,
                        "UNKNOWN_ROOM_MEMBER", "room member must reference an imported user"));
            }
            if (member.joinedAt() == null) {
                issues.add(issue(LegacyV1ConversationKind.ROOM, id,
                        "INVALID_ROOM_JOINED_AT", "room join timestamp is required"));
            }
            if (member.legacyLastReadMessageId() < 0) {
                issues.add(issue(LegacyV1ConversationKind.ROOM, id,
                        "INVALID_ROOM_READ_POINTER", "room read pointer must be nonnegative"));
            }
            V1RoomAdministrator administrator = new V1RoomAdministrator(
                    id, member.legacyUserId());
            if (administrators.contains(administrator)) {
                consumedAdministrators.add(administrator);
            }
        }
        if (!memberIds.contains(room.creatorUserId())) {
            issues.add(issue(LegacyV1ConversationKind.ROOM, id,
                    "CREATOR_NOT_ROOM_MEMBER", "room creator must be an active member"));
        }
    }

    private static List<V1ConversationImportIssue> validateFriendship(
            V1FriendshipRow friendship,
            Set<Long> users,
            Set<Long> ids,
            Set<String> pairs) {
        long id = friendship.legacyFriendshipId();
        List<V1ConversationImportIssue> issues = new ArrayList<>();
        if (id <= 0) {
            issues.add(issue(LegacyV1ConversationKind.FRIENDSHIP, id,
                    "INVALID_FRIENDSHIP_ID", "legacy friendship id must be positive"));
        } else if (!ids.add(id)) {
            issues.add(issue(LegacyV1ConversationKind.FRIENDSHIP, id,
                    "DUPLICATE_FRIENDSHIP_ID", "legacy friendship id is duplicated"));
        }
        if (!users.contains(friendship.firstUserId())
                || !users.contains(friendship.secondUserId())) {
            issues.add(issue(LegacyV1ConversationKind.FRIENDSHIP, id,
                    "UNKNOWN_FRIENDSHIP_USER",
                    "friendship participants must reference imported users"));
        }
        long low = Math.min(friendship.firstUserId(), friendship.secondUserId());
        long high = Math.max(friendship.firstUserId(), friendship.secondUserId());
        if (!pairs.add(low + ":" + high)) {
            issues.add(issue(LegacyV1ConversationKind.FRIENDSHIP, id,
                    "DUPLICATE_FRIENDSHIP_PAIR", "friendship pair is duplicated"));
        }
        if (friendship.createdAt() == null) {
            issues.add(issue(LegacyV1ConversationKind.FRIENDSHIP, id,
                    "INVALID_FRIENDSHIP_CREATED_AT", "friendship timestamp is required"));
        }
        if (friendship.firstLastReadMessageId() < 0
                || friendship.secondLastReadMessageId() < 0) {
            issues.add(issue(LegacyV1ConversationKind.FRIENDSHIP, id,
                    "INVALID_FRIENDSHIP_READ_POINTER",
                    "friendship read pointers must be nonnegative"));
        }
        return List.copyOf(issues);
    }

    private static V1ConversationImportIssue issue(
            LegacyV1ConversationKind kind, long id, String code, String message) {
        return new V1ConversationImportIssue(kind, id, code, message);
    }

    private static boolean boundedCharacters(String value, int minimum, int maximum) {
        if (value == null) {
            return false;
        }
        int characters = value.codePointCount(0, value.length());
        return characters >= minimum && characters <= maximum;
    }

    private static List<V1RoomRow> sortedRooms(List<V1RoomRow> source) {
        List<V1RoomRow> result = new ArrayList<>(source);
        result.sort(Comparator.comparingLong(V1RoomRow::legacyRoomId));
        return result;
    }

    private static List<V1RoomMembershipRow> sortedMemberships(
            List<V1RoomMembershipRow> source) {
        List<V1RoomMembershipRow> result = new ArrayList<>(source);
        result.sort(Comparator.comparingLong(V1RoomMembershipRow::legacyRoomId)
                .thenComparingLong(V1RoomMembershipRow::legacyUserId));
        return result;
    }

    private static List<V1FriendshipRow> sortedFriendships(List<V1FriendshipRow> source) {
        List<V1FriendshipRow> result = new ArrayList<>(source);
        result.sort(Comparator.comparingLong(V1FriendshipRow::legacyFriendshipId));
        return result;
    }

    private static String fingerprint(
            V1ConversationSourceSnapshot source,
            List<V1RoomRow> rooms,
            List<V1RoomMembershipRow> memberships,
            List<V1FriendshipRow> friendships) {
        MessageDigest digest = digest("SHA-256");
        try (DataOutputStream data = new DataOutputStream(
                new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            data.writeInt(source.legacyUserIds().size());
            source.legacyUserIds().stream().sorted().forEach(value -> writeLong(data, value));
            data.writeInt(rooms.size());
            for (V1RoomRow row : rooms) {
                data.writeLong(row.legacyRoomId());
                writeNullable(data, row.name());
                data.writeLong(row.creatorUserId());
                data.writeInt(row.maxMembers());
                writeNullable(data, row.createdAt() == null ? null : row.createdAt().toString());
            }
            data.writeInt(memberships.size());
            for (V1RoomMembershipRow row : memberships) {
                data.writeLong(row.legacyRoomId());
                data.writeLong(row.legacyUserId());
                writeNullable(data, row.joinedAt() == null ? null : row.joinedAt().toString());
                data.writeLong(row.legacyLastReadMessageId());
            }
            data.writeInt(source.roomAdministrators().size());
            source.roomAdministrators().stream()
                    .sorted(Comparator.comparingLong(V1RoomAdministrator::legacyRoomId)
                            .thenComparingLong(V1RoomAdministrator::legacyUserId))
                    .forEach(value -> {
                        writeLong(data, value.legacyRoomId());
                        writeLong(data, value.legacyUserId());
                    });
            data.writeInt(friendships.size());
            for (V1FriendshipRow row : friendships) {
                data.writeLong(row.legacyFriendshipId());
                data.writeLong(row.firstUserId());
                data.writeLong(row.secondUserId());
                writeNullable(data, row.createdAt() == null ? null : row.createdAt().toString());
                data.writeLong(row.firstLastReadMessageId());
                data.writeLong(row.secondLastReadMessageId());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalStateException("in-memory conversation fingerprint failed", exception);
        }
    }

    private static UUID deterministicId(UUID namespace, String prefix, long legacyId) {
        if (legacyId <= 0) {
            throw new IllegalArgumentException("legacyId must be positive");
        }
        MessageDigest digest = digest("SHA-1");
        digest.update(uuidBytes(namespace));
        byte[] hash = digest.digest((prefix + legacyId).getBytes(StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        return new UUID(readLong(hash, 0), readLong(hash, 8));
    }

    private static void writeLong(DataOutputStream data, long value) {
        try {
            data.writeLong(value);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void writeNullable(DataOutputStream data, String value) throws IOException {
        if (value == null) {
            data.writeInt(-1);
            return;
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(encoded.length);
        data.write(encoded);
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is unavailable", exception);
        }
    }

    private static byte[] uuidBytes(UUID value) {
        byte[] bytes = new byte[16];
        putLong(bytes, 0, value.getMostSignificantBits());
        putLong(bytes, 8, value.getLeastSignificantBits());
        return bytes;
    }

    private static void putLong(byte[] bytes, int offset, long value) {
        for (int index = 7; index >= 0; index--) {
            bytes[offset + index] = (byte) value;
            value >>>= 8;
        }
    }

    private static long readLong(byte[] bytes, int offset) {
        long value = 0;
        for (int index = 0; index < 8; index++) {
            value = (value << 8) | (bytes[offset + index] & 0xffL);
        }
        return value;
    }
}
