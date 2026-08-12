package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Atomic monotonic active-participant V1 private read-cursor advancement. */
public final class PostgresLegacyV1DirectReadAdapter implements LegacyV1DirectReadPort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;
    public PostgresLegacyV1DirectReadAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1DirectReadResult markRead(LegacyV1DirectReadCommand command) {
        Objects.requireNonNull(command, "command"); SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(command); }
            catch (SQLException exception) {
                last = exception;
                if (!"40001".equals(exception.getSQLState()) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException("V1 direct read cursor update failed", last);
    }

    private LegacyV1DirectReadResult attempt(LegacyV1DirectReadCommand command)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Target target = lockTarget(connection, command);
                if (target == null) {
                    connection.commit();
                    return LegacyV1DirectReadResult.Rejected.FRIENDSHIP_ACCESS_DENIED;
                }
                long result = Math.max(target.previousSequence(), target.lastSequence());
                long legacyMessageId = latestMappedMessageId(connection,
                        target.conversationId(), target.legacyFriendshipId(), result);
                if (result != target.previousSequence()) update(connection, target, result);
                connection.commit();
                return new LegacyV1DirectReadResult.Marked(target.conversationId(),
                        target.legacyFriendshipId(), target.previousSequence(), result,
                        result != target.previousSequence(), legacyMessageId,
                        target.targetAccountId(), target.targetUsername());
            } catch (RuntimeException | SQLException exception) {
                try { connection.rollback(); } catch (SQLException rollback) {
                    exception.addSuppressed(rollback);
                }
                throw exception;
            }
        }
    }

    private static Target lockTarget(Connection connection, LegacyV1DirectReadCommand command)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT member.conversation_id, member.account_id,
                       mapping.legacy_conversation_id, member.last_read_sequence,
                       conversation.next_sequence - 1 AS last_sequence,
                       peer.id AS target_account_id, peer.username_key AS target_username
                FROM chat.account actor
                JOIN chat.legacy_v1_account_map actor_map ON actor_map.account_id = actor.id
                JOIN chat.conversation_member member ON member.account_id = actor.id
                 AND member.left_at IS NULL
                JOIN chat.conversation conversation ON conversation.id = member.conversation_id
                 AND conversation.kind = 'DIRECT'
                JOIN chat.direct_conversation direct
                  ON direct.conversation_id = conversation.id
                 AND (direct.first_account_id = actor.id OR direct.second_account_id = actor.id)
                JOIN chat.account peer ON peer.id = CASE
                  WHEN direct.first_account_id = actor.id
                  THEN direct.second_account_id ELSE direct.first_account_id END
                 AND peer.disabled_at IS NULL
                JOIN chat.legacy_v1_account_map peer_map ON peer_map.account_id = peer.id
                JOIN chat.conversation_member peer_member
                  ON peer_member.conversation_id = conversation.id
                 AND peer_member.account_id = peer.id AND peer_member.left_at IS NULL
                JOIN chat.legacy_v1_conversation_map mapping
                  ON mapping.conversation_id = conversation.id
                 AND mapping.legacy_kind = 'FRIENDSHIP'
                 AND mapping.legacy_conversation_id = ?
                WHERE actor.id = ? AND actor.disabled_at IS NULL
                  AND mapping.legacy_conversation_id BETWEEN 1 AND 2147483647
                FOR UPDATE OF member, conversation
                """)) {
            statement.setLong(1, command.legacyFriendshipId());
            statement.setObject(2, command.actorAccountId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Target result = new Target(row.getObject("conversation_id", UUID.class),
                        row.getObject("account_id", UUID.class),
                        row.getLong("legacy_conversation_id"),
                        row.getLong("last_read_sequence"), row.getLong("last_sequence"),
                        row.getObject("target_account_id", UUID.class),
                        row.getString("target_username"));
                if (row.next()) throw new SQLException("V1 direct read target duplicated");
                return result;
            }
        }
    }

    private static long latestMappedMessageId(Connection connection, UUID conversationId,
            long legacyFriendshipId, long cursor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT message.id, mapping.legacy_message_id
                FROM chat.message message
                LEFT JOIN chat.legacy_v1_message_map mapping
                 ON mapping.message_id = message.id
                 AND mapping.conversation_id = message.conversation_id
                 AND mapping.legacy_kind = 'FRIENDSHIP'
                 AND mapping.legacy_conversation_id = ?
                WHERE message.conversation_id = ? AND message.conversation_sequence <= ?
                ORDER BY message.conversation_sequence DESC LIMIT 1
                """)) {
            statement.setLong(1, legacyFriendshipId);
            statement.setObject(2, conversationId);
            statement.setLong(3, cursor);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return 0;
                Long legacyId = row.getObject("legacy_message_id", Long.class);
                if (legacyId == null || legacyId <= 0 || legacyId > Integer.MAX_VALUE) {
                    throw new SQLException("V1 direct read latest message mapping is incomplete");
                }
                return legacyId;
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
                throw new SQLException("V1 direct read cursor changed concurrently", "40001");
            }
        }
    }

    private record Target(UUID conversationId, UUID accountId, long legacyFriendshipId,
            long previousSequence, long lastSequence, UUID targetAccountId,
            String targetUsername) { }
}
