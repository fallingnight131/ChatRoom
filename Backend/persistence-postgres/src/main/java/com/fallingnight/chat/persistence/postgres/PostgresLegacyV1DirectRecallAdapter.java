package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectRecallCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectRecallPort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectRecallResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectRecallService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Atomic owner-only recall of one mapped V1 direct message. */
public final class PostgresLegacyV1DirectRecallAdapter implements LegacyV1DirectRecallPort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1DirectRecallAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1DirectRecallResult recall(LegacyV1DirectRecallCommand command) {
        Objects.requireNonNull(command, "command");
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(command); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new MessagePersistenceException("V1 direct recall failed", last);
    }

    private LegacyV1DirectRecallResult attempt(LegacyV1DirectRecallCommand command)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Target target = lockOwnedTarget(connection, command);
                LegacyV1DirectRecallResult result = target == null
                        ? LegacyV1DirectRecallResult.Rejected.RECALL_DENIED
                        : recallLocked(connection, command, target);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private static Target lockOwnedTarget(
            Connection connection, LegacyV1DirectRecallCommand command) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT mapping.legacy_conversation_id, mapping.legacy_message_id,
                       message.id AS message_id, message.conversation_id,
                       message.accepted_at, target.id AS target_account_id,
                       target.username_key AS target_username
                FROM chat.legacy_v1_message_map mapping
                JOIN chat.message message
                  ON message.id = mapping.message_id
                 AND message.conversation_id = mapping.conversation_id
                 AND message.message_type = 1
                JOIN chat.account actor
                  ON actor.id = message.sender_account_id
                 AND actor.id = ? AND actor.disabled_at IS NULL
                JOIN chat.legacy_v1_account_map actor_map ON actor_map.account_id = actor.id
                JOIN chat.direct_conversation direct
                  ON direct.conversation_id = message.conversation_id
                 AND (direct.first_account_id = actor.id
                      OR direct.second_account_id = actor.id)
                JOIN chat.account target
                  ON target.id = CASE
                    WHEN direct.first_account_id = actor.id
                      THEN direct.second_account_id ELSE direct.first_account_id END
                JOIN chat.legacy_v1_account_map target_map ON target_map.account_id = target.id
                JOIN chat.legacy_v1_conversation_map conversation_map
                  ON conversation_map.conversation_id = message.conversation_id
                 AND conversation_map.legacy_kind = mapping.legacy_kind
                 AND conversation_map.legacy_conversation_id =
                     mapping.legacy_conversation_id
                WHERE mapping.legacy_kind = 'FRIENDSHIP'
                  AND mapping.legacy_message_id = ?
                  AND mapping.legacy_content_type IN ('text', 'emoji')
                  AND mapping.legacy_conversation_id BETWEEN 1 AND 2147483647
                FOR UPDATE OF message
                """)) {
            statement.setObject(1, command.actorAccountId());
            statement.setLong(2, command.legacyMessageId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Target target = new Target(
                        row.getLong("legacy_conversation_id"),
                        row.getLong("legacy_message_id"),
                        row.getObject("message_id", UUID.class),
                        row.getObject("conversation_id", UUID.class),
                        row.getObject("accepted_at", OffsetDateTime.class).toInstant(),
                        row.getObject("target_account_id", UUID.class),
                        row.getString("target_username"));
                if (row.next()) throw new SQLException("V1 direct recall target duplicated");
                return target;
            }
        }
    }

    private static LegacyV1DirectRecallResult recallLocked(Connection connection,
            LegacyV1DirectRecallCommand command, Target target) throws SQLException {
        ExistingRecall existing = findExistingRecall(connection, target);
        if (existing != null) {
            if (!existing.actorAccountId().equals(command.actorAccountId())) {
                throw new SQLException("V1 direct recall actor differs from durable event");
            }
            return recalled(true, target, existing.sequence(), existing.occurredAt());
        }
        if (!activeRelationship(connection, target, command.actorAccountId())
                || expired(connection, target.acceptedAt())) {
            return LegacyV1DirectRecallResult.Rejected.RECALL_DENIED;
        }
        Allocation allocation = allocateSequence(connection, target.conversationId());
        insertRecall(connection, target, command.actorAccountId(), allocation);
        return recalled(false, target, allocation.sequence(), allocation.occurredAt());
    }

    private static ExistingRecall findExistingRecall(
            Connection connection, Target target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT recall.conversation_sequence, recall.actor_account_id,
                       COALESCE(entry.occurred_at, entry.ingested_at) AS occurred_at
                FROM chat.message_recall_event recall
                JOIN chat.conversation_entry entry
                  ON entry.conversation_id = recall.conversation_id
                 AND entry.conversation_sequence = recall.conversation_sequence
                 AND entry.entry_kind = 'MESSAGE_RECALLED'
                WHERE recall.conversation_id = ? AND recall.message_id = ?
                """)) {
            statement.setObject(1, target.conversationId());
            statement.setObject(2, target.messageId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                ExistingRecall existing = new ExistingRecall(
                        row.getLong("conversation_sequence"),
                        row.getObject("actor_account_id", UUID.class),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant());
                if (row.next()) throw new SQLException("V1 direct message has multiple recalls");
                return existing;
            }
        }
    }

    private static boolean activeRelationship(Connection connection, Target target, UUID actor)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*)
                FROM chat.conversation_member member
                WHERE member.conversation_id = ? AND member.left_at IS NULL
                  AND member.account_id IN (?, ?)
                """)) {
            statement.setObject(1, target.conversationId());
            statement.setObject(2, actor);
            statement.setObject(3, target.targetAccountId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 direct membership count missing");
                int expected = actor.equals(target.targetAccountId()) ? 1 : 2;
                return row.getInt(1) == expected;
            }
        }
    }

    private static boolean expired(Connection connection, Instant acceptedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT transaction_timestamp() > ? + (? * interval '1 second')")) {
            statement.setObject(1, OffsetDateTime.ofInstant(
                    acceptedAt, java.time.ZoneOffset.UTC));
            statement.setLong(2, LegacyV1DirectRecallService.RECALL_WINDOW.toSeconds());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 direct recall clock missing");
                return row.getBoolean(1);
            }
        }
    }

    private static Allocation allocateSequence(Connection connection, UUID conversationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation
                SET next_sequence = next_sequence + 1, updated_at = transaction_timestamp()
                WHERE id = ? AND kind = 'DIRECT'
                RETURNING next_sequence - 1 AS sequence, transaction_timestamp() AS occurred_at
                """)) {
            statement.setObject(1, conversationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 direct conversation disappeared");
                Allocation allocation = new Allocation(row.getLong("sequence"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant());
                if (row.next()) throw new SQLException("V1 direct sequence duplicated");
                return allocation;
            }
        }
    }

    private static void insertRecall(Connection connection, Target target, UUID actor,
            Allocation allocation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH entry AS (
                    INSERT INTO chat.conversation_entry(
                        conversation_id, conversation_sequence, entry_kind, occurred_at)
                    VALUES (?, ?, 'MESSAGE_RECALLED', ?) RETURNING 1)
                INSERT INTO chat.message_recall_event(
                    conversation_id, conversation_sequence, message_id,
                    actor_account_id, source)
                SELECT ?, ?, ?, ?, 'V2' FROM entry
                """)) {
            statement.setObject(1, target.conversationId());
            statement.setLong(2, allocation.sequence());
            statement.setObject(3, OffsetDateTime.ofInstant(
                    allocation.occurredAt(), java.time.ZoneOffset.UTC));
            statement.setObject(4, target.conversationId());
            statement.setLong(5, allocation.sequence());
            statement.setObject(6, target.messageId());
            statement.setObject(7, actor);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("V1 direct recall event was not inserted");
            }
        }
    }

    private static LegacyV1DirectRecallResult.Recalled recalled(
            boolean duplicate, Target target, long sequence, Instant occurredAt) {
        return new LegacyV1DirectRecallResult.Recalled(
                duplicate, target.legacyFriendshipId(), target.legacyMessageId(),
                sequence, occurredAt, target.targetAccountId(), target.targetUsername());
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }
    private static boolean retryable(SQLException exception) {
        for (SQLException current = exception; current != null;
                current = current.getNextException()) {
            if ("40001".equals(current.getSQLState()) || "23505".equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
    }
    private record Target(long legacyFriendshipId, long legacyMessageId, UUID messageId,
            UUID conversationId, Instant acceptedAt, UUID targetAccountId,
            String targetUsername) { }
    private record ExistingRecall(long sequence, UUID actorAccountId, Instant occurredAt) { }
    private record Allocation(long sequence, Instant occurredAt) { }
}
