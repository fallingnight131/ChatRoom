package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Serializable V1 room kick with membership-generation audit and retry convergence. */
public final class PostgresLegacyV1RoomKickAdapter implements LegacyV1RoomKickPort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1RoomKickAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RoomKickResult kick(LegacyV1RoomKickCommand command) {
        Objects.requireNonNull(command, "command");
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(command); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException("V1 room kick failed", last);
    }

    private LegacyV1RoomKickResult attempt(LegacyV1RoomKickCommand command)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Room room = lockRoom(connection, command.legacyRoomId());
                if (room == null) return commit(connection,
                        LegacyV1RoomKickResult.Rejected.ROOM_ADMIN_REQUIRED);
                Member actor = lockMemberByAccount(connection, room.conversationId(),
                        command.actorAccountId());
                if (actor == null || actor.leftAt() != null
                        || (actor.role() != Role.OWNER && actor.role() != Role.ADMIN)) {
                    return commit(connection,
                            LegacyV1RoomKickResult.Rejected.ROOM_ADMIN_REQUIRED);
                }
                Member target = lockMemberByUsername(connection, room.conversationId(),
                        command.targetUsername());
                if (target == null) return commit(connection,
                        LegacyV1RoomKickResult.Rejected.TARGET_NOT_ACTIVE_MEMBER);
                if (target.leftAt() != null) {
                    LegacyV1RoomKickResult retry = exactRetry(connection, room, actor, target);
                    return commit(connection, retry == null
                            ? LegacyV1RoomKickResult.Rejected.TARGET_NOT_ACTIVE_MEMBER : retry);
                }
                if (target.role() != Role.MEMBER) return commit(connection,
                        LegacyV1RoomKickResult.Rejected.TARGET_ROLE_PROTECTED);

                OffsetDateTime kickedAt = endMembership(connection, room.conversationId(),
                        target.accountId());
                insertAudit(connection, room, actor, target, kickedAt);
                touchConversation(connection, room.conversationId());
                LegacyV1RoomKickResult result = result(
                        room, target, true, kickedAt);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private static Room lockRoom(Connection connection, long legacyRoomId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation.id, conversation.title
                FROM chat.legacy_v1_conversation_map mapping
                JOIN chat.conversation conversation
                  ON conversation.id = mapping.conversation_id
                 AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = conversation.id
                 AND lifecycle.closed_at IS NULL
                WHERE mapping.legacy_kind = 'ROOM'
                  AND mapping.legacy_conversation_id = ?
                FOR UPDATE OF conversation
                """)) {
            statement.setLong(1, legacyRoomId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                String title = row.getString("title");
                if (!safe(title, 100)) throw new SQLException("V1 room kick title is invalid");
                Room result = new Room(row.getObject("id", UUID.class), legacyRoomId, title);
                if (row.next()) throw new SQLException("V1 room kick target duplicated");
                return result;
            }
        }
    }

    private static Member lockMemberByAccount(Connection connection, UUID conversation,
            UUID account) throws SQLException {
        return lockMember(connection, conversation, "account.id = ?", account);
    }

    private static Member lockMemberByUsername(Connection connection, UUID conversation,
            String username) throws SQLException {
        return lockMember(connection, conversation, "account.username_key = ?", username);
    }

    private static Member lockMember(Connection connection, UUID conversation,
            String predicate, Object value) throws SQLException {
        String sql = """
                SELECT account.id, account.username_key, account.display_name,
                       member.role, member.left_at
                FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                JOIN chat.conversation_member member ON member.account_id = account.id
                 AND member.conversation_id = ?
                WHERE account.disabled_at IS NULL AND %s
                FOR UPDATE OF account, member
                """.formatted(predicate);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, conversation); statement.setObject(2, value);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                String username = row.getString("username_key");
                String display = row.getString("display_name");
                if (!safe(username, 128)) throw new SQLException("V1 kick username is invalid");
                if (display == null || display.isBlank()) display = username;
                if (!safe(display, 100)) throw new SQLException("V1 kick display name is invalid");
                Member result = new Member(row.getObject("id", UUID.class), username, display,
                        Role.valueOf(row.getString("role")),
                        row.getObject("left_at", OffsetDateTime.class));
                if (row.next()) throw new SQLException("V1 room kick member duplicated");
                return result;
            }
        } catch (IllegalArgumentException exception) {
            throw new SQLException("V1 room kick member role is invalid", exception);
        }
    }

    private static LegacyV1RoomKickResult exactRetry(Connection connection,
            Room room, Member actor, Member target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT room_name_snapshot, target_username_snapshot,
                       target_display_name_snapshot, kicked_at
                FROM chat.legacy_v1_room_kick_event
                WHERE conversation_id = ? AND actor_account_id = ?
                  AND target_account_id = ? AND kicked_at = ?
                """)) {
            statement.setObject(1, room.conversationId());
            statement.setObject(2, actor.accountId());
            statement.setObject(3, target.accountId());
            statement.setObject(4, target.leftAt());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Member snapshot = new Member(target.accountId(),
                        row.getString("target_username_snapshot"),
                        row.getString("target_display_name_snapshot"), target.role(), target.leftAt());
                Room snapshotRoom = new Room(room.conversationId(), room.legacyRoomId(),
                        row.getString("room_name_snapshot"));
                LegacyV1RoomKickResult result = result(snapshotRoom, snapshot, false,
                        row.getObject("kicked_at", OffsetDateTime.class));
                if (row.next()) throw new SQLException("V1 room kick retry duplicated");
                return result;
            }
        }
    }

    private static OffsetDateTime endMembership(Connection connection, UUID conversation,
            UUID target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation_member
                SET left_at = transaction_timestamp()
                WHERE conversation_id = ? AND account_id = ?
                  AND role = 'MEMBER' AND left_at IS NULL
                RETURNING left_at
                """)) {
            statement.setObject(1, conversation); statement.setObject(2, target);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 room kick membership changed");
                OffsetDateTime result = row.getObject(1, OffsetDateTime.class);
                if (row.next()) throw new SQLException("V1 room kick changed multiple memberships");
                return result;
            }
        }
    }

    private static void insertAudit(Connection connection, Room room, Member actor,
            Member target, OffsetDateTime kickedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.legacy_v1_room_kick_event(
                    conversation_id, actor_account_id, target_account_id, kicked_at,
                    legacy_room_id, room_name_snapshot, target_username_snapshot,
                    target_display_name_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, room.conversationId());
            statement.setObject(2, actor.accountId());
            statement.setObject(3, target.accountId());
            statement.setObject(4, kickedAt);
            statement.setLong(5, room.legacyRoomId());
            statement.setString(6, room.roomName());
            statement.setString(7, target.username());
            statement.setString(8, target.displayName());
            if (statement.executeUpdate() != 1) throw new SQLException("V1 kick audit not inserted");
        }
    }

    private static void touchConversation(Connection connection, UUID conversation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chat.conversation SET updated_at = transaction_timestamp() WHERE id = ?")) {
            statement.setObject(1, conversation);
            if (statement.executeUpdate() != 1) throw new SQLException("V1 kick room disappeared");
        }
    }

    private static LegacyV1RoomKickResult result(Room room, Member target,
            boolean changed, OffsetDateTime kickedAt) {
        return new LegacyV1RoomKickResult.Kicked(room.conversationId(), room.legacyRoomId(),
                room.roomName(), target.accountId(), target.username(), target.displayName(),
                changed, kickedAt.toInstant());
    }

    private static boolean safe(String value, int maximumCodePoints) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= maximumCodePoints
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static <T extends LegacyV1RoomKickResult> T commit(
            Connection connection, T result) throws SQLException {
        connection.commit(); return result;
    }
    private static boolean retryable(SQLException exception) {
        for (SQLException current = exception; current != null;
                current = current.getNextException()) {
            if ("40001".equals(current.getSQLState()) || "40P01".equals(current.getSQLState()))
                return true;
        }
        return false;
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); } catch (SQLException failure) { original.addSuppressed(failure); }
    }
    private enum Role { OWNER, ADMIN, MEMBER }
    private record Room(UUID conversationId, long legacyRoomId, String roomName) { }
    private record Member(UUID accountId, String username, String displayName,
            Role role, OffsetDateTime leftAt) { }
}
