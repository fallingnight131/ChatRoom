package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomRecallCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomRecallPort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomRecallResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomRecallService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Atomic owner-only recall of one mapped V1 room text/emoji message. */
public final class PostgresLegacyV1RoomRecallAdapter implements LegacyV1RoomRecallPort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1RoomRecallAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RoomRecallResult recall(LegacyV1RoomRecallCommand command) {
        Objects.requireNonNull(command, "command");
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(command); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new MessagePersistenceException("V1 room recall failed", last);
    }

    private LegacyV1RoomRecallResult attempt(LegacyV1RoomRecallCommand command)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Target target = lockTarget(connection, command);
                LegacyV1RoomRecallResult result = target == null
                        ? LegacyV1RoomRecallResult.Rejected.ROOM_ACCESS_DENIED
                        : recallLocked(connection, command, target);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private static Target lockTarget(Connection connection, LegacyV1RoomRecallCommand command)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT room.legacy_conversation_id, mapping.legacy_message_id,
                       message.id AS message_id, message.conversation_id,
                       message.sender_account_id, message.accepted_at
                FROM chat.account actor
                JOIN chat.legacy_v1_account_map actor_map ON actor_map.account_id = actor.id
                JOIN chat.legacy_v1_conversation_map room
                  ON room.legacy_kind = 'ROOM' AND room.legacy_conversation_id = ?
                JOIN chat.conversation conversation
                  ON conversation.id = room.conversation_id AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = conversation.id
                 AND lifecycle.closed_at IS NULL
                JOIN chat.legacy_v1_message_map mapping
                  ON mapping.legacy_kind = 'ROOM'
                 AND mapping.legacy_conversation_id = room.legacy_conversation_id
                 AND mapping.conversation_id = room.conversation_id
                 AND mapping.legacy_message_id = ?
                 AND mapping.legacy_content_type IN ('text', 'emoji')
                JOIN chat.message message
                  ON message.id = mapping.message_id
                 AND message.conversation_id = mapping.conversation_id
                 AND message.message_type = 1
                WHERE actor.id = ? AND actor.disabled_at IS NULL
                  AND room.legacy_conversation_id BETWEEN 1 AND 2147483647
                  AND mapping.legacy_message_id BETWEEN 1 AND 2147483647
                FOR UPDATE OF message
                """)) {
            statement.setLong(1, command.legacyRoomId());
            statement.setLong(2, command.legacyMessageId());
            statement.setObject(3, command.actorAccountId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Target target = new Target(row.getLong("legacy_conversation_id"),
                        row.getLong("legacy_message_id"),
                        row.getObject("message_id", UUID.class),
                        row.getObject("conversation_id", UUID.class),
                        row.getObject("sender_account_id", UUID.class),
                        row.getObject("accepted_at", OffsetDateTime.class).toInstant());
                if (row.next()) throw new SQLException("V1 room recall target duplicated");
                return target;
            }
        }
    }

    private static LegacyV1RoomRecallResult recallLocked(Connection connection,
            LegacyV1RoomRecallCommand command, Target target) throws SQLException {
        ExistingRecall existing = findExistingRecall(connection, target);
        if (existing != null) {
            return existing.actorAccountId().equals(command.actorAccountId())
                    ? recalled(true, target, existing.sequence(), existing.occurredAt())
                    : LegacyV1RoomRecallResult.Rejected.RECALL_REJECTED;
        }
        if (!activeMember(connection, target.conversationId(), command.actorAccountId())) {
            return LegacyV1RoomRecallResult.Rejected.ROOM_ACCESS_DENIED;
        }
        if (!target.senderAccountId().equals(command.actorAccountId())
                || expired(connection, target.acceptedAt())) {
            return LegacyV1RoomRecallResult.Rejected.RECALL_REJECTED;
        }
        Allocation allocation = allocateSequence(connection, target.conversationId());
        insertRecall(connection, target, command.actorAccountId(), allocation);
        return recalled(false, target, allocation.sequence(), allocation.occurredAt());
    }

    private static ExistingRecall findExistingRecall(Connection connection, Target target)
            throws SQLException {
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
                ExistingRecall result = new ExistingRecall(row.getLong("conversation_sequence"),
                        row.getObject("actor_account_id", UUID.class),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant());
                if (row.next()) throw new SQLException("V1 room message has multiple recalls");
                return result;
            }
        }
    }

    private static boolean activeMember(Connection connection, UUID conversationId, UUID actor)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM chat.conversation_member member
                    WHERE member.conversation_id = ? AND member.account_id = ?
                      AND member.left_at IS NULL)
                """)) {
            statement.setObject(1, conversationId);
            statement.setObject(2, actor);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 room recall membership missing");
                return row.getBoolean(1);
            }
        }
    }

    private static boolean expired(Connection connection, Instant acceptedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT transaction_timestamp() > ? + (? * interval '1 second')")) {
            statement.setObject(1, OffsetDateTime.ofInstant(
                    acceptedAt, java.time.ZoneOffset.UTC));
            statement.setLong(2, LegacyV1RoomRecallService.RECALL_WINDOW.toSeconds());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 room recall clock missing");
                return row.getBoolean(1);
            }
        }
    }

    private static Allocation allocateSequence(Connection connection, UUID conversationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation
                SET next_sequence = next_sequence + 1, updated_at = transaction_timestamp()
                WHERE id = ? AND kind = 'GROUP'
                RETURNING next_sequence - 1 AS sequence, transaction_timestamp() AS occurred_at
                """)) {
            statement.setObject(1, conversationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 room conversation disappeared");
                Allocation result = new Allocation(row.getLong("sequence"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant());
                if (row.next()) throw new SQLException("V1 room recall sequence duplicated");
                return result;
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
                throw new SQLException("V1 room recall event was not inserted");
            }
        }
    }

    private static LegacyV1RoomRecallResult.Recalled recalled(
            boolean duplicate, Target target, long sequence, Instant occurredAt) {
        return new LegacyV1RoomRecallResult.Recalled(duplicate, target.conversationId(),
                target.legacyRoomId(), target.legacyMessageId(), sequence, occurredAt);
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
    private record Target(long legacyRoomId, long legacyMessageId, UUID messageId,
            UUID conversationId, UUID senderAccountId, Instant acceptedAt) { }
    private record ExistingRecall(long sequence, UUID actorAccountId, Instant occurredAt) { }
    private record Allocation(long sequence, Instant occurredAt) { }
}
