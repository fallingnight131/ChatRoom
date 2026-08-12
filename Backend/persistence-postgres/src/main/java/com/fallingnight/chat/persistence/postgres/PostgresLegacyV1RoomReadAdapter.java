package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Atomic monotonic active-member V1 room read-cursor advancement. */
public final class PostgresLegacyV1RoomReadAdapter implements LegacyV1RoomReadPort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;
    public PostgresLegacyV1RoomReadAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RoomReadResult markRead(LegacyV1RoomReadCommand command) {
        Objects.requireNonNull(command, "command"); SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(command); }
            catch (SQLException exception) {
                last = exception;
                if (!"40001".equals(exception.getSQLState()) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException("V1 room read cursor update failed", last);
    }

    private LegacyV1RoomReadResult attempt(LegacyV1RoomReadCommand command) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Target target = lockTarget(connection, command);
                if (target == null) {
                    connection.commit();
                    return LegacyV1RoomReadResult.Rejected.ROOM_ACCESS_DENIED;
                }
                long result = Math.max(target.previousSequence(), target.lastSequence());
                if (result != target.previousSequence()) update(connection, target, result);
                connection.commit();
                return new LegacyV1RoomReadResult.Marked(target.conversationId(),
                        target.legacyRoomId(), target.previousSequence(), result,
                        result != target.previousSequence());
            } catch (RuntimeException | SQLException exception) {
                try { connection.rollback(); } catch (SQLException rollback) {
                    exception.addSuppressed(rollback);
                }
                throw exception;
            }
        }
    }

    private static Target lockTarget(Connection connection, LegacyV1RoomReadCommand command)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT member.conversation_id, mapping.legacy_conversation_id,
                       member.last_read_sequence, conversation.next_sequence - 1 AS last_sequence
                FROM chat.account actor
                JOIN chat.legacy_v1_account_map actor_map ON actor_map.account_id = actor.id
                JOIN chat.conversation_member member ON member.account_id = actor.id
                 AND member.left_at IS NULL
                JOIN chat.conversation conversation ON conversation.id = member.conversation_id
                 AND conversation.kind = 'GROUP'
                JOIN chat.legacy_v1_conversation_map mapping
                  ON mapping.conversation_id = conversation.id AND mapping.legacy_kind = 'ROOM'
                 AND mapping.legacy_conversation_id = ?
                WHERE actor.id = ? AND actor.disabled_at IS NULL
                  AND mapping.legacy_conversation_id BETWEEN 1 AND 2147483647
                FOR UPDATE OF member, conversation
                """)) {
            statement.setLong(1, command.legacyRoomId());
            statement.setObject(2, command.actorAccountId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Target result = new Target(row.getObject("conversation_id", UUID.class),
                        command.actorAccountId(),
                        row.getLong("legacy_conversation_id"),
                        row.getLong("last_read_sequence"), row.getLong("last_sequence"));
                if (row.next()) throw new SQLException("V1 room read target duplicated");
                return result;
            }
        }
    }

    private static void update(Connection connection, Target target, long sequence)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation_member
                SET last_read_sequence = GREATEST(last_read_sequence, ?)
                WHERE conversation_id = ? AND account_id = ?
                  AND last_read_sequence = ? AND left_at IS NULL
                """)) {
            statement.setLong(1, sequence); statement.setObject(2, target.conversationId());
            statement.setObject(3, target.accountId());
            statement.setLong(4, target.previousSequence());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("V1 room read cursor changed concurrently", "40001");
            }
        }
    }

    private record Target(UUID conversationId, UUID accountId, long legacyRoomId,
            long previousSequence, long lastSequence) { }
}
