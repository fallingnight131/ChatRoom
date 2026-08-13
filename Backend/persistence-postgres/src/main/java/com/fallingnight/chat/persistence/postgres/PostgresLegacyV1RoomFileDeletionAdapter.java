package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFileDeletionIntent;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFileDeletionPort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFileDeletionResult;
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

/** Atomic idempotent administrator deletion of selected mapped V1 room files. */
public final class PostgresLegacyV1RoomFileDeletionAdapter
        implements LegacyV1RoomFileDeletionPort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1RoomFileDeletionAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RoomFileDeletionResult delete(
            LegacyV1RoomFileDeletionIntent intent) {
        Objects.requireNonNull(intent, "intent");
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(intent); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new MessagePersistenceException("V1 room file deletion failed", last);
    }

    private LegacyV1RoomFileDeletionResult attempt(
            LegacyV1RoomFileDeletionIntent intent) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Target room = lockAuthorizedRoom(connection, intent);
                if (room == null) {
                    connection.commit();
                    return LegacyV1RoomFileDeletionResult.Rejected.ROOM_ADMIN_REQUIRED;
                }
                Existing existing = findExisting(connection, intent.actorAccountId(),
                        intent.clientOperationId());
                if (existing != null) {
                    LegacyV1RoomFileDeletionResult result = existing.fingerprint()
                            .equals(intent.commandFingerprint())
                            ? result(true, room, intent.clientOperationId(),
                                    existing.messageIds(), existing.fileIds(),
                                    existing.sequence(), existing.occurredAt(),
                                    usedSpace(connection, room.conversationId()))
                            : LegacyV1RoomFileDeletionResult.Rejected
                                    .CLIENT_OPERATION_ID_CONFLICT;
                    connection.commit();
                    return result;
                }
                List<FileTarget> files = lockFiles(connection, room, intent.legacyFileIds());
                Allocation allocation = allocateSequence(connection, room.conversationId());
                if (!files.isEmpty()) {
                    revokeAttachments(connection, files, allocation.occurredAt());
                    deleteRecallEntries(connection, room.conversationId(), files);
                    deleteMessagesAndEntries(connection, room.conversationId(), files);
                }
                List<Long> messageIds = files.stream().map(FileTarget::legacyMessageId).toList();
                List<Long> fileIds = files.stream().map(FileTarget::legacyFileId).toList();
                insertDeletionEvent(connection, room, intent, messageIds, fileIds, allocation);
                long used = usedSpace(connection, room.conversationId());
                connection.commit();
                return result(false, room, intent.clientOperationId(), messageIds, fileIds,
                        allocation.sequence(), allocation.occurredAt(), used);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private static Target lockAuthorizedRoom(Connection connection,
            LegacyV1RoomFileDeletionIntent intent) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT room.conversation_id, room.legacy_conversation_id,
                       resource.total_file_space, actor.display_name, actor.username_key
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
                JOIN chat.group_resource_policy resource
                  ON resource.conversation_id = conversation.id
                WHERE actor.id = ? AND actor.disabled_at IS NULL
                FOR UPDATE OF conversation
                """)) {
            statement.setLong(1, intent.legacyRoomId());
            statement.setObject(2, intent.actorAccountId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                String display = row.getString("display_name");
                Target target = new Target(row.getObject("conversation_id", UUID.class),
                        row.getLong("legacy_conversation_id"),
                        row.getLong("total_file_space"),
                        display == null || display.isEmpty()
                                ? row.getString("username_key") : display);
                if (row.next()) throw new SQLException("V1 room deletion target duplicated");
                return target;
            }
        }
    }

    private static Existing findExisting(Connection connection, UUID actor, String operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT deletion.command_fingerprint, deletion.conversation_sequence,
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
                        longs(row.getArray("message_ids")), longs(row.getArray("file_ids")),
                        row.getLong("conversation_sequence"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant());
                if (row.next()) throw new SQLException("V1 room deletion operation duplicated");
                return result;
            }
        }
    }

    private static List<FileTarget> lockFiles(Connection connection, Target room,
            List<Long> requested) throws SQLException {
        Array ids = connection.createArrayOf("bigint", requested.toArray());
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT file_map.legacy_file_id, message_map.legacy_message_id,
                       attachment.id AS attachment_id, message.id AS message_id,
                       message.conversation_sequence
                FROM chat.legacy_v1_attachment_map file_map
                JOIN chat.attachment attachment
                  ON attachment.id = file_map.attachment_id
                 AND attachment.conversation_id = file_map.conversation_id
                 AND attachment.state = 'READY'
                JOIN chat.message message
                  ON message.attachment_id = attachment.id
                 AND message.conversation_id = attachment.conversation_id
                 AND message.message_type = 2
                JOIN chat.legacy_v1_message_map message_map
                  ON message_map.message_id = message.id
                 AND message_map.conversation_id = message.conversation_id
                 AND message_map.legacy_kind = 'ROOM'
                 AND message_map.legacy_conversation_id = file_map.legacy_conversation_id
                 AND message_map.legacy_content_type IN ('file', 'image', 'video')
                WHERE file_map.legacy_kind = 'ROOM'
                  AND file_map.legacy_conversation_id = ?
                  AND file_map.conversation_id = ?
                  AND file_map.legacy_file_id = ANY (?)
                ORDER BY file_map.legacy_file_id
                FOR UPDATE OF attachment, message
                """)) {
            statement.setLong(1, room.legacyRoomId());
            statement.setObject(2, room.conversationId());
            statement.setArray(3, ids);
            try (ResultSet row = statement.executeQuery()) {
                List<FileTarget> result = new ArrayList<>();
                while (row.next()) result.add(new FileTarget(
                        row.getLong("legacy_file_id"), row.getLong("legacy_message_id"),
                        row.getObject("attachment_id", UUID.class),
                        row.getObject("message_id", UUID.class),
                        row.getLong("conversation_sequence")));
                return List.copyOf(result);
            }
        } finally {
            ids.free();
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

    private static void revokeAttachments(Connection connection, List<FileTarget> files,
            Instant occurredAt) throws SQLException {
        Array ids = connection.createArrayOf("uuid",
                files.stream().map(FileTarget::attachmentId).toArray());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.attachment SET state = 'REVOKED', revoked_at = ?
                WHERE id = ANY (?) AND state = 'READY'
                """)) {
            statement.setObject(1, OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
            statement.setArray(2, ids);
            if (statement.executeUpdate() != files.size()) {
                throw new SQLException("V1 room attachments changed during deletion");
            }
        } finally {
            ids.free();
        }
    }

    private static void deleteRecallEntries(Connection connection, UUID conversationId,
            List<FileTarget> files) throws SQLException {
        Array messageIds = connection.createArrayOf("uuid",
                files.stream().map(FileTarget::messageId).toArray());
        try (PreparedStatement sequences = connection.prepareStatement("""
                SELECT conversation_sequence FROM chat.message_recall_event
                WHERE conversation_id = ? AND message_id = ANY (?)
                """)) {
            sequences.setObject(1, conversationId);
            sequences.setArray(2, messageIds);
            List<Long> recalled = new ArrayList<>();
            try (ResultSet row = sequences.executeQuery()) {
                while (row.next()) recalled.add(row.getLong(1));
            }
            if (recalled.isEmpty()) return;
            Array recallSequences = connection.createArrayOf("bigint", recalled.toArray());
            try (PreparedStatement recalls = connection.prepareStatement("""
                    DELETE FROM chat.message_recall_event
                    WHERE conversation_id = ? AND conversation_sequence = ANY (?)
                    """)) {
                recalls.setObject(1, conversationId);
                recalls.setArray(2, recallSequences);
                if (recalls.executeUpdate() != recalled.size()) {
                    throw new SQLException("V1 room recalls changed during deletion");
                }
            } finally {
                recallSequences.free();
            }
            recallSequences = connection.createArrayOf("bigint", recalled.toArray());
            try (PreparedStatement entries = connection.prepareStatement("""
                    DELETE FROM chat.conversation_entry
                    WHERE conversation_id = ? AND conversation_sequence = ANY (?)
                      AND entry_kind = 'MESSAGE_RECALLED'
                    """)) {
                entries.setObject(1, conversationId);
                entries.setArray(2, recallSequences);
                if (entries.executeUpdate() != recalled.size()) {
                    throw new SQLException("V1 room recall entries changed during deletion");
                }
            } finally {
                recallSequences.free();
            }
        } finally {
            messageIds.free();
        }
    }

    private static void deleteMessagesAndEntries(Connection connection, UUID conversationId,
            List<FileTarget> files) throws SQLException {
        Array ids = connection.createArrayOf("uuid",
                files.stream().map(FileTarget::messageId).toArray());
        try (PreparedStatement messages = connection.prepareStatement(
                        "DELETE FROM chat.message WHERE id = ANY (?)")) {
            messages.setArray(1, ids);
            if (messages.executeUpdate() != files.size()) {
                throw new SQLException("V1 room messages changed during deletion");
            }
        } finally {
            ids.free();
        }
        Array sequences = connection.createArrayOf("bigint",
                files.stream().map(FileTarget::messageSequence).toArray());
        try (PreparedStatement entries = connection.prepareStatement("""
                DELETE FROM chat.conversation_entry
                WHERE conversation_id = ? AND conversation_sequence = ANY (?)
                  AND entry_kind = 'MESSAGE'
                """)) {
            entries.setObject(1, conversationId);
            entries.setArray(2, sequences);
            if (entries.executeUpdate() != files.size()) {
                throw new SQLException("V1 room message entries changed during deletion");
            }
        } finally {
            sequences.free();
        }
    }

    private static void insertDeletionEvent(Connection connection, Target room,
            LegacyV1RoomFileDeletionIntent intent, List<Long> messageIds, List<Long> fileIds,
            Allocation allocation) throws SQLException {
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
                    SELECT ?, ?, ?, 'V2', 'selected', ?, ?, ?::jsonb, ?::jsonb, 0, ?, ?
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
            statement.setString(index++, intent.clientOperationId());
            statement.setString(index++, intent.commandFingerprint());
            statement.setString(index++, json(messageIds));
            statement.setString(index++, json(fileIds));
            statement.setInt(index++, fileIds.size());
            statement.setString(index++, room.operatorName());
            statement.setLong(index++, legacyEventId);
            statement.setLong(index++, room.legacyRoomId());
            statement.setObject(index++, room.conversationId());
            statement.setLong(index, allocation.sequence());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("V1 room file deletion event was not inserted");
            }
        }
    }

    private static long usedSpace(Connection connection, UUID conversationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(sum(attachment.byte_size), 0)
                FROM chat.legacy_v1_attachment_map file_map
                JOIN chat.attachment attachment
                  ON attachment.id = file_map.attachment_id
                 AND attachment.conversation_id = file_map.conversation_id
                 AND attachment.state = 'READY'
                WHERE file_map.legacy_kind = 'ROOM' AND file_map.conversation_id = ?
                """)) {
            statement.setObject(1, conversationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 room quota missing");
                return row.getLong(1);
            }
        }
    }

    private static LegacyV1RoomFileDeletionResult.Deleted result(boolean duplicate,
            Target room, String operationId, List<Long> messageIds, List<Long> fileIds,
            long sequence, Instant occurredAt, long used) {
        return new LegacyV1RoomFileDeletionResult.Deleted(duplicate, room.conversationId(),
                room.legacyRoomId(), operationId, messageIds, fileIds, sequence, occurredAt,
                used, room.maxFileSpace());
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
            if ("40001".equals(current.getSQLState()) || "23505".equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }

    private record Target(UUID conversationId, long legacyRoomId,
            long maxFileSpace, String operatorName) { }
    private record Existing(String fingerprint, List<Long> messageIds, List<Long> fileIds,
            long sequence, Instant occurredAt) { }
    private record FileTarget(long legacyFileId, long legacyMessageId,
            UUID attachmentId, UUID messageId, long messageSequence) { }
    private record Allocation(long sequence, Instant occurredAt) { }
}
