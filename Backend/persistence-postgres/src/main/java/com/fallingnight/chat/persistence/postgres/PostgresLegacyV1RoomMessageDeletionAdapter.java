package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMessageDeletionIntent;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMessageDeletionMode;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMessageDeletionPort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMessageDeletionResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMessageDeletionService;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Atomic idempotent implementation of all V1 room administrative delete modes. */
public final class PostgresLegacyV1RoomMessageDeletionAdapter
        implements LegacyV1RoomMessageDeletionPort {
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_TARGET_MESSAGES = 100_000;
    private final DataSource dataSource;

    public PostgresLegacyV1RoomMessageDeletionAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RoomMessageDeletionResult delete(
            LegacyV1RoomMessageDeletionIntent intent) {
        Objects.requireNonNull(intent, "intent");
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(intent); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new MessagePersistenceException("V1 room message deletion failed", last);
    }

    private LegacyV1RoomMessageDeletionResult attempt(
            LegacyV1RoomMessageDeletionIntent intent) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Room room = lockAuthorizedRoom(connection, intent);
                if (room == null) {
                    connection.commit();
                    return LegacyV1RoomMessageDeletionResult.Rejected.ROOM_ADMIN_REQUIRED;
                }
                Existing existing = findExisting(connection, intent.actorAccountId(),
                        intent.clientOperationId());
                if (existing != null) {
                    LegacyV1RoomMessageDeletionResult result = existing.fingerprint()
                            .equals(intent.commandFingerprint())
                            ? result(true, room, intent.clientOperationId(), existing.mode(),
                                    existing.messageIds(), existing.fileIds(), existing.cutoff(),
                                    existing.deletedCount(), existing.sequence(),
                                    existing.occurredAt())
                            : LegacyV1RoomMessageDeletionResult.Rejected
                                    .CLIENT_OPERATION_ID_CONFLICT;
                    connection.commit();
                    return result;
                }
                List<MessageTarget> targets = lockTargets(connection, room, intent);
                if (targets.size() > MAX_TARGET_MESSAGES
                        || targets.stream().filter(target -> target.legacyFileId() != null)
                                .count() > LegacyV1RoomMessageDeletionService.MAX_FILES) {
                    connection.commit();
                    return LegacyV1RoomMessageDeletionResult.Rejected.DELETE_SCOPE_TOO_LARGE;
                }
                Allocation allocation = allocateSequence(connection, room.conversationId());
                if (!targets.isEmpty()) {
                    revokeReadyAttachments(connection, targets, allocation.occurredAt());
                    deleteRecallEntries(connection, room.conversationId(), targets);
                    deleteMessagesAndEntries(connection, room.conversationId(), targets);
                }
                List<Long> eventMessageIds = intent.mode()
                        == LegacyV1RoomMessageDeletionMode.SELECTED
                        ? targets.stream().map(MessageTarget::legacyMessageId).toList()
                        : List.of();
                List<Long> fileIds = targets.stream().map(MessageTarget::legacyFileId)
                        .filter(Objects::nonNull).distinct().toList();
                insertDeletionEvent(connection, room, intent, eventMessageIds, fileIds,
                        targets.size(), allocation);
                connection.commit();
                return result(false, room, intent.clientOperationId(), intent.mode(),
                        eventMessageIds, fileIds, intent.cutoffEpochMillis(), targets.size(),
                        allocation.sequence(), allocation.occurredAt());
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private static Room lockAuthorizedRoom(Connection connection,
            LegacyV1RoomMessageDeletionIntent intent) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT room.conversation_id, room.legacy_conversation_id,
                       COALESCE(NULLIF(actor.display_name, ''), actor.username_key) operator_name
                FROM chat.account actor
                JOIN chat.legacy_v1_account_map actor_map ON actor_map.account_id = actor.id
                JOIN chat.legacy_v1_conversation_map room
                  ON room.legacy_kind = 'ROOM' AND room.legacy_conversation_id = ?
                JOIN chat.conversation conversation
                  ON conversation.id = room.conversation_id AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = conversation.id AND lifecycle.closed_at IS NULL
                JOIN chat.conversation_member member
                  ON member.conversation_id = conversation.id
                 AND member.account_id = actor.id AND member.left_at IS NULL
                 AND member.role IN ('OWNER', 'ADMIN')
                WHERE actor.id = ? AND actor.disabled_at IS NULL
                FOR UPDATE OF conversation
                """)) {
            statement.setLong(1, intent.legacyRoomId());
            statement.setObject(2, intent.actorAccountId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Room result = new Room(row.getObject("conversation_id", UUID.class),
                        row.getLong("legacy_conversation_id"), row.getString("operator_name"));
                if (row.next()) throw new SQLException("V1 room deletion target duplicated");
                return result;
            }
        }
    }

    private static Existing findExisting(Connection connection, UUID actor, String operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT deletion.command_fingerprint, deletion.mode,
                       deletion.cutoff_epoch_ms, deletion.deleted_count,
                       deletion.conversation_sequence,
                       COALESCE(entry.occurred_at, entry.ingested_at) AS occurred_at,
                       ARRAY(SELECT value::bigint FROM jsonb_array_elements_text(
                           deletion.message_ids) WITH ORDINALITY value(value, position)
                           ORDER BY position) AS message_ids,
                       ARRAY(SELECT value::bigint FROM jsonb_array_elements_text(
                           deletion.file_ids) WITH ORDINALITY value(value, position)
                           ORDER BY position) AS file_ids
                FROM chat.messages_deleted_event deletion
                JOIN chat.conversation_entry entry
                  ON entry.conversation_id = deletion.conversation_id
                 AND entry.conversation_sequence = deletion.conversation_sequence
                WHERE deletion.source = 'V2' AND deletion.actor_account_id = ?
                  AND deletion.client_operation_id = ?
                """)) {
            statement.setObject(1, actor);
            statement.setString(2, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Existing result = new Existing(row.getString("command_fingerprint"),
                        LegacyV1RoomMessageDeletionMode.parse(row.getString("mode"))
                                .orElseThrow(() -> new SQLException("invalid deletion mode")),
                        longs(row.getArray("message_ids")), longs(row.getArray("file_ids")),
                        row.getLong("cutoff_epoch_ms"), row.getInt("deleted_count"),
                        row.getLong("conversation_sequence"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant());
                if (row.next()) throw new SQLException("V1 room deletion operation duplicated");
                return result;
            }
        }
    }

    private static List<MessageTarget> lockTargets(Connection connection, Room room,
            LegacyV1RoomMessageDeletionIntent intent) throws SQLException {
        String predicate = switch (intent.mode()) {
            case SELECTED -> "AND mapping.legacy_message_id = ANY (?)";
            case ALL -> "";
            case BEFORE -> "AND message.accepted_at < ?";
            case AFTER -> "AND message.accepted_at > ?";
        };
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT mapping.legacy_message_id, message.id AS message_id,
                       message.conversation_sequence, attachment.id AS attachment_id,
                       attachment.state AS attachment_state, file_map.legacy_file_id
                FROM chat.message message
                JOIN chat.legacy_v1_message_map mapping
                  ON mapping.message_id = message.id
                 AND mapping.conversation_id = message.conversation_id
                 AND mapping.legacy_kind = 'ROOM'
                 AND mapping.legacy_conversation_id = ?
                LEFT JOIN chat.attachment attachment
                  ON attachment.id = message.attachment_id
                 AND attachment.conversation_id = message.conversation_id
                LEFT JOIN chat.legacy_v1_attachment_map file_map
                  ON file_map.attachment_id = attachment.id
                 AND file_map.conversation_id = attachment.conversation_id
                 AND file_map.legacy_kind = 'ROOM'
                 AND file_map.legacy_conversation_id = mapping.legacy_conversation_id
                WHERE message.conversation_id = ?
                """ + predicate + "\n" + """
                ORDER BY mapping.legacy_message_id
                LIMIT 100001
                FOR UPDATE OF message
                """)) {
            statement.setLong(1, room.legacyRoomId());
            statement.setObject(2, room.conversationId());
            Array selected = null;
            if (intent.mode() == LegacyV1RoomMessageDeletionMode.SELECTED) {
                selected = connection.createArrayOf("bigint", intent.legacyMessageIds().toArray());
                statement.setArray(3, selected);
            } else if (intent.mode().usesCutoff()) {
                statement.setObject(3, OffsetDateTime.ofInstant(
                        Instant.ofEpochMilli(intent.cutoffEpochMillis()), ZoneOffset.UTC));
            }
            try (ResultSet row = statement.executeQuery()) {
                List<MessageTarget> result = new ArrayList<>();
                while (row.next()) {
                    long fileId = row.getLong("legacy_file_id");
                    Long nullableFileId = row.wasNull() ? null : fileId;
                    result.add(new MessageTarget(row.getLong("legacy_message_id"),
                            row.getObject("message_id", UUID.class),
                            row.getLong("conversation_sequence"),
                            row.getObject("attachment_id", UUID.class),
                            row.getString("attachment_state"),
                            nullableFileId));
                }
                return List.copyOf(result);
            } finally {
                if (selected != null) selected.free();
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
                if (!row.next()) throw new SQLException("V1 room deletion conversation missing");
                return new Allocation(row.getLong("sequence"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant());
            }
        }
    }

    private static void revokeReadyAttachments(Connection connection,
            List<MessageTarget> targets, Instant occurredAt) throws SQLException {
        List<UUID> ids = targets.stream().filter(target -> "READY".equals(target.attachmentState()))
                .map(MessageTarget::attachmentId).toList();
        if (ids.isEmpty()) return;
        Array values = connection.createArrayOf("uuid", ids.toArray());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.attachment SET state = 'REVOKED', revoked_at = ?
                WHERE id = ANY (?) AND state = 'READY'
                """)) {
            statement.setObject(1, OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
            statement.setArray(2, values);
            if (statement.executeUpdate() != ids.size()) {
                throw new SQLException("V1 room attachments changed during deletion");
            }
        } finally { values.free(); }
    }

    private static void deleteRecallEntries(Connection connection, UUID conversationId,
            List<MessageTarget> targets) throws SQLException {
        Array messageIds = connection.createArrayOf("uuid",
                targets.stream().map(MessageTarget::messageId).toArray());
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH removed AS (
                    DELETE FROM chat.message_recall_event
                    WHERE conversation_id = ? AND message_id = ANY (?)
                    RETURNING conversation_sequence)
                DELETE FROM chat.conversation_entry entry USING removed
                WHERE entry.conversation_id = ?
                  AND entry.conversation_sequence = removed.conversation_sequence
                  AND entry.entry_kind = 'MESSAGE_RECALLED'
                """)) {
            statement.setObject(1, conversationId);
            statement.setArray(2, messageIds);
            statement.setObject(3, conversationId);
            statement.executeUpdate();
        } finally { messageIds.free(); }
    }

    private static void deleteMessagesAndEntries(Connection connection, UUID conversationId,
            List<MessageTarget> targets) throws SQLException {
        Array messageIds = connection.createArrayOf("uuid",
                targets.stream().map(MessageTarget::messageId).toArray());
        try (PreparedStatement messages = connection.prepareStatement(
                "DELETE FROM chat.message WHERE id = ANY (?)")) {
            messages.setArray(1, messageIds);
            if (messages.executeUpdate() != targets.size()) {
                throw new SQLException("V1 room messages changed during deletion");
            }
        } finally { messageIds.free(); }
        Array sequences = connection.createArrayOf("bigint",
                targets.stream().map(MessageTarget::messageSequence).toArray());
        try (PreparedStatement entries = connection.prepareStatement("""
                DELETE FROM chat.conversation_entry
                WHERE conversation_id = ? AND conversation_sequence = ANY (?)
                  AND entry_kind = 'MESSAGE'
                """)) {
            entries.setObject(1, conversationId);
            entries.setArray(2, sequences);
            if (entries.executeUpdate() != targets.size()) {
                throw new SQLException("V1 room message entries changed during deletion");
            }
        } finally { sequences.free(); }
    }

    private static void insertDeletionEvent(Connection connection, Room room,
            LegacyV1RoomMessageDeletionIntent intent, List<Long> messageIds,
            List<Long> fileIds, int deletedCount, Allocation allocation) throws SQLException {
        long legacyEventId;
        try (PreparedStatement sequence = connection.prepareStatement(
                "SELECT nextval('chat.legacy_v1_deletion_event_id_seq')")) {
            try (ResultSet row = sequence.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 deletion event identity missing");
                legacyEventId = row.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH entry AS (
                    INSERT INTO chat.conversation_entry(
                        conversation_id, conversation_sequence, entry_kind, occurred_at)
                    VALUES (?, ?, 'MESSAGES_DELETED', ?) RETURNING 1),
                deletion AS (
                    INSERT INTO chat.messages_deleted_event(
                        conversation_id, conversation_sequence, actor_account_id, source,
                        mode, client_operation_id, command_fingerprint, message_ids,
                        file_ids, cutoff_epoch_ms, deleted_count, operator_name_snapshot)
                    SELECT ?, ?, ?, 'V2', ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?
                    FROM entry RETURNING 1)
                INSERT INTO chat.legacy_v1_deletion_event_map(
                    legacy_event_id, legacy_room_id, conversation_id, conversation_sequence)
                SELECT ?, ?, ?, ? FROM deletion
                """)) {
            int index = 1;
            statement.setObject(index++, room.conversationId());
            statement.setLong(index++, allocation.sequence());
            statement.setObject(index++, OffsetDateTime.ofInstant(
                    allocation.occurredAt(), ZoneOffset.UTC));
            statement.setObject(index++, room.conversationId());
            statement.setLong(index++, allocation.sequence());
            statement.setObject(index++, intent.actorAccountId());
            statement.setString(index++, intent.mode().wireValue());
            statement.setString(index++, intent.clientOperationId());
            statement.setString(index++, intent.commandFingerprint());
            statement.setString(index++, json(messageIds));
            statement.setString(index++, json(fileIds));
            statement.setLong(index++, intent.cutoffEpochMillis());
            statement.setInt(index++, deletedCount);
            statement.setString(index++, room.operatorName());
            statement.setLong(index++, legacyEventId);
            statement.setLong(index++, room.legacyRoomId());
            statement.setObject(index++, room.conversationId());
            statement.setLong(index, allocation.sequence());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("V1 room deletion event was not inserted");
            }
        }
    }

    private static LegacyV1RoomMessageDeletionResult.Deleted result(boolean duplicate,
            Room room, String operationId, LegacyV1RoomMessageDeletionMode mode,
            List<Long> messageIds, List<Long> fileIds, long cutoff, int deletedCount,
            long sequence, Instant occurredAt) {
        return new LegacyV1RoomMessageDeletionResult.Deleted(duplicate,
                room.conversationId(), room.legacyRoomId(), operationId, mode, messageIds,
                fileIds, cutoff, deletedCount, sequence, occurredAt);
    }

    private static String json(List<Long> values) {
        return "[" + String.join(",", values.stream().map(String::valueOf).toList()) + "]";
    }

    private static List<Long> longs(Array array) throws SQLException {
        if (array == null) throw new SQLException("V1 deletion identity array missing");
        try {
            Object[] values = (Object[]) array.getArray();
            List<Long> result = new ArrayList<>(values.length);
            for (Object value : values) result.add(((Number) value).longValue());
            return List.copyOf(result);
        } finally { array.free(); }
    }

    private static boolean retryable(SQLException exception) {
        for (SQLException current = exception; current != null;
                current = current.getNextException()) {
            if ("40001".equals(current.getSQLState()) || "23505".equals(current.getSQLState()))
                return true;
        }
        return false;
    }

    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }

    private record Room(UUID conversationId, long legacyRoomId, String operatorName) { }
    private record Existing(String fingerprint, LegacyV1RoomMessageDeletionMode mode,
            List<Long> messageIds, List<Long> fileIds, long cutoff, int deletedCount,
            long sequence, Instant occurredAt) { }
    private record MessageTarget(long legacyMessageId, UUID messageId,
            long messageSequence, UUID attachmentId, String attachmentState,
            Long legacyFileId) { }
    private record Allocation(long sequence, Instant occurredAt) { }
}
