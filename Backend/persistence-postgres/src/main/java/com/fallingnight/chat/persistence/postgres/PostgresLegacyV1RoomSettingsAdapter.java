package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import java.sql.*;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Exact active-member room-settings projection from canonical PostgreSQL policy rows. */
public final class PostgresLegacyV1RoomSettingsAdapter implements LegacyV1RoomSettingsPort {
    private final DataSource dataSource;

    public PostgresLegacyV1RoomSettingsAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public QueryResult read(UUID actor, long roomId) {
        Objects.requireNonNull(actor, "actor");
        if (roomId <= 0 || roomId > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid V1 room settings query");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try {
                QueryResult result = query(connection, actor, roomId);
                connection.commit(); return result;
            } catch (RuntimeException | SQLException exception) {
                try { connection.rollback(); }
                catch (SQLException failure) { exception.addSuppressed(failure); }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException("V1 room settings read failed", exception);
        }
    }

    private static QueryResult query(Connection connection, UUID actor, long roomId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT resource.max_file_size, resource.total_file_space,
                       resource.max_file_count, admission.max_members
                FROM chat.account actor
                JOIN chat.legacy_v1_account_map actor_map ON actor_map.account_id = actor.id
                JOIN chat.legacy_v1_conversation_map room
                  ON room.legacy_kind = 'ROOM' AND room.legacy_conversation_id = ?
                JOIN chat.conversation conversation
                  ON conversation.id = room.conversation_id AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = conversation.id
                 AND lifecycle.closed_at IS NULL
                JOIN chat.conversation_member membership
                  ON membership.conversation_id = conversation.id
                 AND membership.account_id = actor.id AND membership.left_at IS NULL
                JOIN chat.group_resource_policy resource
                  ON resource.conversation_id = conversation.id
                JOIN chat.group_admission_policy admission
                  ON admission.conversation_id = conversation.id
                WHERE actor.id = ? AND actor.disabled_at IS NULL
                """)) {
            statement.setLong(1, roomId); statement.setObject(2, actor);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return QueryResult.Rejected.ROOM_ACCESS_DENIED;
                LegacyV1RoomSettings settings;
                try {
                    settings = new LegacyV1RoomSettings(
                            row.getLong("max_file_size"), row.getLong("total_file_space"),
                            row.getInt("max_file_count"), row.getInt("max_members"));
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("V1 room settings projection is invalid", exception);
                }
                if (row.next()) throw new SQLException("V1 room settings target duplicated");
                return new QueryResult.Authorized(settings);
            }
        }
    }
}
