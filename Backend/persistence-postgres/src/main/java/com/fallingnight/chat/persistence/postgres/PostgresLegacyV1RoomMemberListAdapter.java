package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Authorized active-lifecycle V1 room member projection. */
public final class PostgresLegacyV1RoomMemberListAdapter
        implements LegacyV1RoomMemberListPort {
    private final DataSource dataSource;
    public PostgresLegacyV1RoomMemberListAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public QueryResult list(UUID actor, long roomId, int limitPlusOne) {
        Objects.requireNonNull(actor, "actor");
        if (roomId <= 0 || roomId > Integer.MAX_VALUE
                || limitPlusOne < 1 || limitPlusOne > 1001) {
            throw new IllegalArgumentException("invalid V1 room member query");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try {
                UUID conversation = authorizedConversation(connection, actor, roomId);
                if (conversation == null) {
                    connection.commit(); return QueryResult.Rejected.ROOM_ACCESS_DENIED;
                }
                List<LegacyV1RoomMemberEntry> result = readMembers(
                        connection, conversation, limitPlusOne);
                if (result.isEmpty()) {
                    throw new SQLException("authorized V1 room has no active members");
                }
                connection.commit();
                return new QueryResult.Authorized(result);
            } catch (RuntimeException | SQLException exception) {
                try { connection.rollback(); }
                catch (SQLException failure) { exception.addSuppressed(failure); }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException(
                    "V1 room member list failed", exception);
        }
    }

    private static UUID authorizedConversation(Connection connection, UUID actor, long roomId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation.id
                FROM chat.account actor
                JOIN chat.legacy_v1_account_map actor_map ON actor_map.account_id = actor.id
                JOIN chat.legacy_v1_conversation_map room
                  ON room.legacy_kind = 'ROOM' AND room.legacy_conversation_id = ?
                JOIN chat.conversation conversation
                  ON conversation.id = room.conversation_id AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = conversation.id
                 AND lifecycle.closed_at IS NULL
                JOIN chat.conversation_member actor_member
                  ON actor_member.conversation_id = conversation.id
                 AND actor_member.account_id = actor.id AND actor_member.left_at IS NULL
                WHERE actor.id = ? AND actor.disabled_at IS NULL
                """)) {
            statement.setLong(1, roomId); statement.setObject(2, actor);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                UUID result = row.getObject(1, UUID.class);
                if (row.next()) throw new SQLException("V1 room member target duplicated");
                return result;
            }
        }
    }

    private static List<LegacyV1RoomMemberEntry> readMembers(
            Connection connection, UUID conversation, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT member.account_id, member.role, account.username_key,
                       account.display_name, account.disabled_at, mapping.legacy_user_id
                FROM chat.conversation_member member
                LEFT JOIN chat.account account ON account.id = member.account_id
                LEFT JOIN chat.legacy_v1_account_map mapping
                  ON mapping.account_id = member.account_id
                WHERE member.conversation_id = ? AND member.left_at IS NULL
                ORDER BY account.username_key, member.account_id
                LIMIT ?
                """)) {
            statement.setObject(1, conversation); statement.setInt(2, limit);
            List<LegacyV1RoomMemberEntry> result = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    Long legacyId = row.getObject("legacy_user_id", Long.class);
                    String username = row.getString("username_key");
                    String displayName = row.getString("display_name");
                    if (legacyId == null || legacyId <= 0 || legacyId > Integer.MAX_VALUE
                            || username == null || displayName == null
                            || row.getObject("disabled_at") != null) {
                        throw new SQLException("V1 room member projection is incomplete");
                    }
                    result.add(new LegacyV1RoomMemberEntry(
                            row.getObject("account_id", UUID.class), username, displayName,
                            parseRole(row.getString("role"))));
                }
            }
            return List.copyOf(result);
        }
    }

    private static LegacyV1RoomMemberEntry.Role parseRole(String value) throws SQLException {
        try { return LegacyV1RoomMemberEntry.Role.valueOf(value); }
        catch (RuntimeException exception) {
            throw new SQLException("unsupported V1 room member role", exception);
        }
    }
}
