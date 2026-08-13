package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** Serializable canonical V1 room dissolution with durable exact-retry identity. */
public final class PostgresLegacyV1RoomDissolutionAdapter
        implements LegacyV1RoomDissolutionPort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1RoomDissolutionAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RoomDissolutionResult dissolve(
            LegacyV1RoomDissolutionIntent intent) {
        Objects.requireNonNull(intent, "intent"); SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(intent); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException("V1 room dissolution failed", last);
    }

    private LegacyV1RoomDissolutionResult attempt(LegacyV1RoomDissolutionIntent intent)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                if (!lockEligibleActor(connection, intent.actorAccountId())) {
                    connection.commit();
                    return LegacyV1RoomDissolutionResult.Rejected.ROOM_ADMIN_REQUIRED;
                }
                Room room = lockRoom(connection, intent.legacyRoomId());
                if (room == null) {
                    connection.commit();
                    return LegacyV1RoomDissolutionResult.Rejected.NOT_FOUND;
                }
                Prior prior = prior(connection, room.conversationId());
                if (prior != null) {
                    LegacyV1RoomDissolutionResult result = prior.actorAccountId().equals(
                            intent.actorAccountId())
                            ? dissolved(room.conversationId(), prior.legacyRoomId(),
                                    prior.roomName(), Set.of(), false, prior.dissolvedAt())
                            : LegacyV1RoomDissolutionResult.Rejected.NOT_FOUND;
                    connection.commit(); return result;
                }
                if (room.closedAt() != null || !isActiveAdmin(connection,
                        room.conversationId(), intent.actorAccountId())) {
                    connection.commit();
                    return room.closedAt() != null
                            ? LegacyV1RoomDissolutionResult.Rejected.NOT_FOUND
                            : LegacyV1RoomDissolutionResult.Rejected.ROOM_ADMIN_REQUIRED;
                }
                Set<UUID> audience = lockMappedAudience(connection, room.conversationId());
                if (!audience.contains(intent.actorAccountId())) {
                    throw new SQLException("V1 room dissolution audience excludes actor");
                }
                OffsetDateTime occurredAt = databaseNow(connection);
                insertOperation(connection, room, intent, occurredAt);
                endMemberships(connection, room.conversationId(), occurredAt);
                closeRoom(connection, room.conversationId(), occurredAt);
                deleteCredential(connection, room.conversationId());
                revokeAttachments(connection, room.conversationId(), occurredAt);
                LegacyV1RoomDissolutionResult result = dissolved(room.conversationId(),
                        intent.legacyRoomId(), room.roomName(), audience, true, occurredAt);
                connection.commit(); return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        }
    }

    private static boolean lockEligibleActor(Connection connection, UUID actor)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account.id FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE account.id = ? AND account.disabled_at IS NULL
                FOR SHARE OF account
                """)) {
            statement.setObject(1, actor);
            try (ResultSet row = statement.executeQuery()) { return row.next() && !row.next(); }
        }
    }

    private static Room lockRoom(Connection connection, long legacyRoomId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation.id, conversation.title, lifecycle.closed_at
                FROM chat.legacy_v1_conversation_map mapping
                JOIN chat.conversation conversation ON conversation.id = mapping.conversation_id
                  AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = conversation.id
                WHERE mapping.legacy_kind = 'ROOM' AND mapping.legacy_conversation_id = ?
                FOR UPDATE OF conversation, lifecycle
                """)) {
            statement.setLong(1, legacyRoomId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Room result = new Room(row.getObject("id", UUID.class),
                        row.getString("title"), row.getObject("closed_at", OffsetDateTime.class));
                if (row.next()) throw new SQLException("V1 room dissolution mapping duplicated");
                return result;
            }
        }
    }

    private static Prior prior(Connection connection, UUID conversation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT actor_account_id, legacy_room_id, room_name, dissolved_at
                FROM chat.legacy_v1_room_dissolution WHERE conversation_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, conversation);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Prior result = new Prior(row.getObject("actor_account_id", UUID.class),
                        row.getLong("legacy_room_id"), row.getString("room_name"),
                        row.getObject("dissolved_at", OffsetDateTime.class));
                if (row.next()) throw new SQLException("V1 room dissolution duplicated");
                return result;
            }
        }
    }

    private static boolean isActiveAdmin(Connection connection, UUID conversation, UUID actor)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM chat.conversation_member
                WHERE conversation_id = ? AND account_id = ? AND left_at IS NULL
                  AND role IN ('OWNER', 'ADMIN')
                FOR UPDATE
                """)) {
            statement.setObject(1, conversation); statement.setObject(2, actor);
            try (ResultSet row = statement.executeQuery()) { return row.next() && !row.next(); }
        }
    }

    private static Set<UUID> lockMappedAudience(Connection connection, UUID conversation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT member.account_id, account.disabled_at, mapping.legacy_user_id
                FROM chat.conversation_member member
                JOIN chat.account account ON account.id = member.account_id
                LEFT JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = member.account_id
                WHERE member.conversation_id = ? AND member.left_at IS NULL
                ORDER BY member.account_id FOR UPDATE OF member, account
                """)) {
            statement.setObject(1, conversation); LinkedHashSet<UUID> result = new LinkedHashSet<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    if (row.getObject("disabled_at") != null
                            || row.getObject("legacy_user_id") == null) {
                        throw new SQLException("V1 room dissolution audience is incomplete");
                    }
                    if (!result.add(row.getObject("account_id", UUID.class))) {
                        throw new SQLException("V1 room dissolution audience duplicated");
                    }
                }
            }
            if (result.isEmpty()) throw new SQLException("V1 room dissolution audience is empty");
            return Set.copyOf(result);
        }
    }

    private static OffsetDateTime databaseNow(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT transaction_timestamp()")) {
            if (!row.next()) throw new SQLException("database time unavailable");
            return row.getObject(1, OffsetDateTime.class);
        }
    }

    private static void insertOperation(Connection connection, Room room,
            LegacyV1RoomDissolutionIntent intent, OffsetDateTime occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.legacy_v1_room_dissolution
                    (conversation_id, actor_account_id, legacy_room_id, room_name, dissolved_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, room.conversationId());
            statement.setObject(2, intent.actorAccountId());
            statement.setLong(3, intent.legacyRoomId()); statement.setString(4, room.roomName());
            statement.setObject(5, occurredAt); requireOne(statement, "room dissolution operation");
        }
    }

    private static void endMemberships(Connection connection, UUID conversation,
            OffsetDateTime occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation_member SET left_at = ?
                WHERE conversation_id = ? AND left_at IS NULL
                """)) {
            statement.setObject(1, occurredAt); statement.setObject(2, conversation);
            if (statement.executeUpdate() <= 0) throw new SQLException("no room members dissolved");
        }
    }

    private static void closeRoom(Connection connection, UUID conversation,
            OffsetDateTime occurredAt) throws SQLException {
        try (PreparedStatement lifecycle = connection.prepareStatement("""
                UPDATE chat.group_lifecycle SET closed_at = ?, updated_at = ?
                WHERE conversation_id = ? AND closed_at IS NULL
                """)) {
            lifecycle.setObject(1, occurredAt); lifecycle.setObject(2, occurredAt);
            lifecycle.setObject(3, conversation); requireOne(lifecycle, "room lifecycle closure");
        }
        try (PreparedStatement conversationUpdate = connection.prepareStatement(
                "UPDATE chat.conversation SET updated_at = ? WHERE id = ?")) {
            conversationUpdate.setObject(1, occurredAt);
            conversationUpdate.setObject(2, conversation);
            requireOne(conversationUpdate, "room dissolution timestamp");
        }
    }

    private static void deleteCredential(Connection connection, UUID conversation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM chat.group_join_credential WHERE conversation_id = ?")) {
            statement.setObject(1, conversation); statement.executeUpdate();
        }
    }

    private static void revokeAttachments(Connection connection, UUID conversation,
            OffsetDateTime occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.attachment SET state = 'REVOKED', revoked_at = ?
                WHERE conversation_id = ? AND state IN ('UPLOAD_PENDING', 'READY')
                """)) {
            statement.setObject(1, occurredAt); statement.setObject(2, conversation);
            statement.executeUpdate();
        }
    }

    private static LegacyV1RoomDissolutionResult.Dissolved dissolved(UUID conversation,
            long roomId, String roomName, Set<UUID> audience, boolean changed,
            OffsetDateTime occurredAt) {
        return new LegacyV1RoomDissolutionResult.Dissolved(conversation, roomId, roomName,
                audience, changed, occurredAt.toInstant());
    }
    private static void requireOne(PreparedStatement statement, String operation)
            throws SQLException {
        if (statement.executeUpdate() != 1)
            throw new SQLException(operation + " affected unexpected rows");
    }
    private static boolean retryable(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException())
            if ("40001".equals(current.getSQLState())) return true;
        return false;
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }
    private record Room(UUID conversationId, String roomName, OffsetDateTime closedAt) { }
    private record Prior(UUID actorAccountId, long legacyRoomId, String roomName,
            OffsetDateTime dissolvedAt) { }
}
