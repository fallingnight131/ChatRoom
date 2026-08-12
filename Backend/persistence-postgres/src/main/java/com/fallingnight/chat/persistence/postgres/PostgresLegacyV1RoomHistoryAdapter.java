package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomHistoryDeletion;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomHistoryMessage;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomHistoryPort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomHistoryQuery;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomHistoryResult;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Complete active-membership V1 room text/emoji and deletion history snapshot. */
public final class PostgresLegacyV1RoomHistoryAdapter implements LegacyV1RoomHistoryPort {
    private final DataSource dataSource;

    public PostgresLegacyV1RoomHistoryAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RoomHistoryResult read(LegacyV1RoomHistoryQuery query) {
        Objects.requireNonNull(query, "query");
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                Target target = resolveTarget(connection, query.accountId(), query.legacyRoomId());
                if (target == null) {
                    connection.commit();
                    return LegacyV1RoomHistoryResult.Rejected.ROOM_ACCESS_DENIED;
                }
                if (query.afterSequence() != null
                        && query.afterSequence() > target.lastSequence()) {
                    connection.commit();
                    return LegacyV1RoomHistoryResult.Rejected.INVALID_SEQUENCE_CURSOR;
                }
                requireCompleteCompatibilityState(connection, target.conversationId());
                boolean sequenceMode = query.afterSequence() != null;
                Projection projection = sequenceMode
                        ? readAfter(connection, target, query.afterSequence(), query.limit())
                        : new Projection(readLatest(connection, target,
                                query.beforeEpochMillis(), query.limit()), List.of(), false, 0);
                long next = sequenceMode && projection.hasMore()
                        ? projection.nextSequence() : target.lastSequence();
                connection.commit();
                return new LegacyV1RoomHistoryResult.Page(target.legacyRoomId(), sequenceMode,
                        projection.messages(), projection.events(), next,
                        target.lastSequence(), projection.hasMore());
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new MessagePersistenceException("V1 room history read failed", exception);
        }
    }

    private static Target resolveTarget(Connection connection, UUID accountId, long roomId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT mapping.conversation_id, mapping.legacy_conversation_id,
                       conversation.next_sequence - 1 AS last_sequence
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
                """)) {
            statement.setLong(1, roomId);
            statement.setObject(2, accountId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Target result = new Target(row.getObject(1, UUID.class), row.getLong(2),
                        row.getLong(3));
                if (row.next()) throw new SQLException("V1 room history target duplicated");
                return result;
            }
        }
    }

    private static void requireCompleteCompatibilityState(
            Connection connection, UUID conversationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1 FROM chat.conversation_entry entry
                    WHERE entry.conversation_id = ?
                      AND entry.entry_kind NOT IN ('MESSAGE', 'MESSAGE_RECALLED', 'MESSAGES_DELETED'))
                  OR EXISTS (
                    SELECT 1 FROM chat.conversation_entry entry
                    LEFT JOIN chat.message message
                      ON message.conversation_id = entry.conversation_id
                     AND message.conversation_sequence = entry.conversation_sequence
                    WHERE entry.conversation_id = ? AND entry.entry_kind = 'MESSAGE'
                      AND message.id IS NULL)
                  OR EXISTS (
                    SELECT 1 FROM chat.conversation_entry entry
                    LEFT JOIN chat.message_recall_event recall
                      ON recall.conversation_id = entry.conversation_id
                     AND recall.conversation_sequence = entry.conversation_sequence
                    WHERE entry.conversation_id = ? AND entry.entry_kind = 'MESSAGE_RECALLED'
                      AND recall.message_id IS NULL)
                  OR EXISTS (
                    SELECT 1 FROM chat.conversation_entry entry
                    LEFT JOIN chat.messages_deleted_event deletion
                      ON deletion.conversation_id = entry.conversation_id
                     AND deletion.conversation_sequence = entry.conversation_sequence
                    LEFT JOIN chat.legacy_v1_deletion_event_map mapping
                      ON mapping.conversation_id = entry.conversation_id
                     AND mapping.conversation_sequence = entry.conversation_sequence
                    WHERE entry.conversation_id = ? AND entry.entry_kind = 'MESSAGES_DELETED'
                      AND (deletion.conversation_id IS NULL OR deletion.source <> 'V1_IMPORT'
                        OR mapping.legacy_event_id IS NULL
                        OR mapping.legacy_event_id NOT BETWEEN 1 AND 2147483647
                        OR jsonb_array_length(deletion.message_ids) > 1000
                        OR jsonb_array_length(deletion.file_ids) > 1000
                        OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(
                            deletion.message_ids) value WHERE CASE
                              WHEN value ~ '^[1-9][0-9]{0,9}$'
                              THEN value::numeric > 2147483647 ELSE true END)
                        OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(
                            deletion.file_ids) value WHERE CASE
                              WHEN value ~ '^[1-9][0-9]{0,9}$'
                              THEN value::numeric > 2147483647 ELSE true END)
                        OR EXISTS (SELECT value FROM jsonb_array_elements_text(
                            deletion.message_ids) value GROUP BY value HAVING count(*) > 1)
                        OR EXISTS (SELECT value FROM jsonb_array_elements_text(
                            deletion.file_ids) value GROUP BY value HAVING count(*) > 1)))
                  OR EXISTS (
                    SELECT 1 FROM chat.message message
                    LEFT JOIN chat.legacy_v1_message_map mapping
                      ON mapping.message_id = message.id
                     AND mapping.conversation_id = message.conversation_id
                     AND mapping.legacy_kind = 'ROOM'
                    LEFT JOIN chat.account sender ON sender.id = message.sender_account_id
                    LEFT JOIN chat.legacy_v1_account_map sender_map
                      ON sender_map.account_id = sender.id
                    WHERE message.conversation_id = ?
                      AND (message.message_type <> 1 OR mapping.message_id IS NULL
                        OR mapping.legacy_content_type IS NULL OR sender_map.account_id IS NULL
                        OR mapping.legacy_message_id NOT BETWEEN 1 AND 2147483647))
                  OR EXISTS (
                    SELECT 1 FROM chat.message_recall_event recall
                    LEFT JOIN chat.legacy_v1_message_map mapping
                      ON mapping.message_id = recall.message_id
                     AND mapping.conversation_id = recall.conversation_id
                     AND mapping.legacy_kind = 'ROOM'
                    WHERE recall.conversation_id = ? AND mapping.message_id IS NULL)
                  OR EXISTS (
                    SELECT recall.message_id FROM chat.message_recall_event recall
                    WHERE recall.conversation_id = ? GROUP BY recall.message_id HAVING count(*) > 1)
                """)) {
            for (int index = 1; index <= 7; index++) statement.setObject(index, conversationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("compatibility check returned no row");
                if (row.getBoolean(1)) {
                    throw new SQLException("V1 room history compatibility state is incomplete");
                }
            }
        }
    }

    private static Projection readAfter(
            Connection connection, Target target, long after, int limit) throws SQLException {
        List<LegacyV1RoomHistoryMessage> messages = new ArrayList<>();
        List<LegacyV1RoomHistoryDeletion> events = new ArrayList<>();
        List<Long> sequences = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sequenceQuery())) {
            statement.setObject(1, target.conversationId());
            statement.setObject(2, target.conversationId());
            statement.setObject(3, target.conversationId());
            statement.setLong(4, after);
            statement.setInt(5, limit + 1);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    sequences.add(row.getLong("sync_sequence"));
                    if ("MESSAGE".equals(row.getString("item_kind"))) messages.add(message(row));
                    else events.add(deletion(row));
                }
            }
        }
        boolean hasMore = sequences.size() > limit;
        if (hasMore) {
            long removed = sequences.removeLast();
            if (!messages.isEmpty() && messages.getLast().syncSequence() == removed)
                messages.removeLast();
            else if (!events.isEmpty() && events.getLast().sequence() == removed)
                events.removeLast();
            else throw new SQLException("V1 room history page tail is inconsistent");
        }
        long next = sequences.isEmpty() ? after : sequences.getLast();
        return new Projection(messages, events, hasMore, next);
    }

    private static List<LegacyV1RoomHistoryMessage> readLatest(Connection connection,
            Target target, long beforeEpochMillis, int limit) throws SQLException {
        String before = beforeEpochMillis > 0 ? "AND message.accepted_at < ? " : "";
        String sql = "SELECT * FROM (" + messageQuery() + before
                + "ORDER BY message.conversation_sequence DESC LIMIT ?) latest "
                + "ORDER BY sequence ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, target.legacyRoomId());
            statement.setObject(2, target.conversationId());
            int parameter = 3;
            if (beforeEpochMillis > 0) statement.setObject(parameter++, OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(beforeEpochMillis), java.time.ZoneOffset.UTC));
            statement.setInt(parameter, limit);
            List<LegacyV1RoomHistoryMessage> result = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(message(row));
            }
            return result;
        }
    }

    private static String sequenceQuery() {
        return """
                WITH items AS (
                    SELECT 'MESSAGE' AS item_kind, message.id AS message_id,
                           GREATEST(message.conversation_sequence,
                               COALESCE(recall.conversation_sequence, 0)) AS sync_sequence
                    FROM chat.message message
                    LEFT JOIN chat.message_recall_event recall
                      ON recall.conversation_id = message.conversation_id
                     AND recall.message_id = message.id
                    WHERE message.conversation_id = ?
                    UNION ALL
                    SELECT 'DELETION', NULL, deletion.conversation_sequence
                    FROM chat.messages_deleted_event deletion
                    WHERE deletion.conversation_id = ?
                )
                SELECT items.item_kind, items.sync_sequence,
                       mapping.legacy_message_id, message.conversation_sequence AS sequence,
                       recall.conversation_sequence AS mutation_sequence,
                       message.client_message_id, account.username_key, account.display_name,
                       message.payload, mapping.legacy_content_type, message.accepted_at,
                       deletion_map.legacy_event_id, deletion.operator_name_snapshot,
                       deletion.client_operation_id, deletion.mode,
                       ARRAY(SELECT value::bigint FROM jsonb_array_elements_text(
                           deletion.message_ids) WITH ORDINALITY value(value, position)
                           ORDER BY position) AS deletion_message_ids,
                       ARRAY(SELECT value::bigint FROM jsonb_array_elements_text(
                           deletion.file_ids) WITH ORDINALITY value(value, position)
                           ORDER BY position) AS deletion_file_ids,
                       deletion.cutoff_epoch_ms, deletion.deleted_count,
                       deletion_entry.occurred_at AS deletion_occurred_at
                FROM items
                LEFT JOIN chat.message message ON message.id = items.message_id
                LEFT JOIN chat.legacy_v1_message_map mapping
                  ON mapping.message_id = message.id AND mapping.legacy_kind = 'ROOM'
                LEFT JOIN chat.account account ON account.id = message.sender_account_id
                LEFT JOIN chat.message_recall_event recall
                  ON recall.conversation_id = message.conversation_id
                 AND recall.message_id = message.id
                LEFT JOIN chat.messages_deleted_event deletion
                  ON items.item_kind = 'DELETION'
                 AND deletion.conversation_id = ?
                 AND deletion.conversation_sequence = items.sync_sequence
                LEFT JOIN chat.conversation_entry deletion_entry
                  ON deletion_entry.conversation_id = deletion.conversation_id
                 AND deletion_entry.conversation_sequence = deletion.conversation_sequence
                LEFT JOIN chat.legacy_v1_deletion_event_map deletion_map
                  ON deletion_map.conversation_id = deletion.conversation_id
                 AND deletion_map.conversation_sequence = deletion.conversation_sequence
                WHERE items.sync_sequence > ? ORDER BY items.sync_sequence ASC LIMIT ?
                """;
    }

    private static String messageQuery() {
        return """
                SELECT mapping.legacy_message_id, message.conversation_sequence AS sequence,
                       recall.conversation_sequence AS mutation_sequence,
                       GREATEST(message.conversation_sequence,
                           COALESCE(recall.conversation_sequence, 0)) AS sync_sequence,
                       message.client_message_id, account.username_key, account.display_name,
                       message.payload, mapping.legacy_content_type, message.accepted_at
                FROM chat.message message
                JOIN chat.legacy_v1_message_map mapping
                  ON mapping.message_id = message.id AND mapping.conversation_id = message.conversation_id
                 AND mapping.legacy_kind = 'ROOM' AND mapping.legacy_conversation_id = ?
                JOIN chat.account account ON account.id = message.sender_account_id
                JOIN chat.legacy_v1_account_map sender_map ON sender_map.account_id = account.id
                LEFT JOIN chat.message_recall_event recall
                  ON recall.conversation_id = message.conversation_id AND recall.message_id = message.id
                WHERE message.conversation_id = ?
                """;
    }

    private static LegacyV1RoomHistoryMessage message(ResultSet row) throws SQLException {
        Long mutation = row.getObject("mutation_sequence", Long.class);
        return new LegacyV1RoomHistoryMessage(row.getLong("legacy_message_id"),
                row.getLong("sequence"), mutation, row.getLong("sync_sequence"),
                row.getString("client_message_id"), row.getString("username_key"),
                displayName(row), decodeUtf8(row.getBytes("payload")),
                row.getString("legacy_content_type"), mutation != null,
                row.getObject("accepted_at", OffsetDateTime.class).toInstant());
    }

    private static LegacyV1RoomHistoryDeletion deletion(ResultSet row) throws SQLException {
        return new LegacyV1RoomHistoryDeletion(row.getLong("legacy_event_id"),
                row.getLong("sync_sequence"), row.getString("operator_name_snapshot"),
                row.getString("client_operation_id"), row.getString("mode"),
                longs(row.getArray("deletion_message_ids")),
                longs(row.getArray("deletion_file_ids")), row.getLong("cutoff_epoch_ms"),
                row.getInt("deleted_count"),
                row.getObject("deletion_occurred_at", OffsetDateTime.class).toInstant());
    }

    private static List<Long> longs(Array array) throws SQLException {
        if (array == null) throw new SQLException("V1 deletion identity array is missing");
        try {
            Object[] values = (Object[]) array.getArray();
            List<Long> result = new ArrayList<>(values.length);
            for (Object value : values) result.add(((Number) value).longValue());
            return List.copyOf(result);
        } finally { array.free(); }
    }

    private static String displayName(ResultSet row) throws SQLException {
        String display = row.getString("display_name");
        return display == null || display.isEmpty() ? row.getString("username_key") : display;
    }

    private static String decodeUtf8(byte[] bytes) throws SQLException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new SQLException("V1 room history payload is not UTF-8", exception);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }

    private record Target(UUID conversationId, long legacyRoomId, long lastSequence) { }
    private record Projection(List<LegacyV1RoomHistoryMessage> messages,
            List<LegacyV1RoomHistoryDeletion> events, boolean hasMore, long nextSequence) { }
}
