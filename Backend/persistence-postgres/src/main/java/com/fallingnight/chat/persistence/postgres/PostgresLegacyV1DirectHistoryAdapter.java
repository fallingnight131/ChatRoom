package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryMessage;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryPort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryQuery;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryResult;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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

/** Complete active-friendship V1 text/emoji history snapshot. */
public final class PostgresLegacyV1DirectHistoryAdapter implements LegacyV1DirectHistoryPort {
    private final DataSource dataSource;

    public PostgresLegacyV1DirectHistoryAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1DirectHistoryResult read(LegacyV1DirectHistoryQuery query) {
        Objects.requireNonNull(query, "query");
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                Target target = resolveTarget(connection, query.accountId(), query.targetUsername());
                if (target == null) {
                    connection.commit();
                    return LegacyV1DirectHistoryResult.Rejected.FRIENDSHIP_ACCESS_DENIED;
                }
                if (query.afterSequence() != null
                        && query.afterSequence() > target.lastSequence()) {
                    connection.commit();
                    return LegacyV1DirectHistoryResult.Rejected.INVALID_SEQUENCE_CURSOR;
                }
                requireCompleteCompatibilityState(connection, target.conversationId());
                boolean sequenceMode = query.afterSequence() != null;
                List<LegacyV1DirectHistoryMessage> messages = sequenceMode
                        ? readAfter(connection, target, query.afterSequence(), query.limit())
                        : readLatest(connection, target, query.beforeEpochMillis(), query.limit());
                boolean hasMore = sequenceMode && messages.size() > query.limit();
                if (hasMore) messages.removeLast();
                long next = sequenceMode && hasMore && !messages.isEmpty()
                        ? messages.getLast().syncSequence() : target.lastSequence();
                connection.commit();
                return new LegacyV1DirectHistoryResult.Page(
                        target.legacyFriendshipId(), target.targetUsername(), sequenceMode,
                        messages, next, target.lastSequence(), hasMore);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new MessagePersistenceException("V1 direct history read failed", exception);
        }
    }

    private static Target resolveTarget(
            Connection connection, UUID accountId, String targetUsername) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT target.username_key, direct.conversation_id,
                       mapping.legacy_conversation_id, conversation.next_sequence - 1 AS last_sequence
                FROM chat.account actor
                JOIN chat.legacy_v1_account_map actor_map ON actor_map.account_id = actor.id
                JOIN chat.account target ON target.username_key = ? AND target.disabled_at IS NULL
                JOIN chat.legacy_v1_account_map target_map ON target_map.account_id = target.id
                JOIN chat.direct_conversation direct
                  ON direct.first_account_id = LEAST(actor.id, target.id)
                 AND direct.second_account_id = GREATEST(actor.id, target.id)
                JOIN chat.conversation conversation ON conversation.id = direct.conversation_id
                 AND conversation.kind = 'DIRECT'
                JOIN chat.conversation_member actor_member
                  ON actor_member.conversation_id = direct.conversation_id
                 AND actor_member.account_id = actor.id AND actor_member.left_at IS NULL
                JOIN chat.conversation_member target_member
                  ON target_member.conversation_id = direct.conversation_id
                 AND target_member.account_id = target.id AND target_member.left_at IS NULL
                JOIN chat.legacy_v1_conversation_map mapping
                  ON mapping.conversation_id = direct.conversation_id
                 AND mapping.legacy_kind = 'FRIENDSHIP'
                WHERE actor.id = ? AND actor.disabled_at IS NULL
                  AND mapping.legacy_conversation_id BETWEEN 1 AND 2147483647
                """)) {
            statement.setString(1, targetUsername);
            statement.setObject(2, accountId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Target result = new Target(row.getString("username_key"),
                        row.getObject("conversation_id", UUID.class),
                        row.getLong("legacy_conversation_id"), row.getLong("last_sequence"));
                if (row.next()) throw new SQLException("V1 direct history target duplicated");
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
                      AND entry.entry_kind NOT IN ('MESSAGE', 'MESSAGE_RECALLED'))
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
                    SELECT 1 FROM chat.message message
                    LEFT JOIN chat.legacy_v1_message_map mapping
                      ON mapping.message_id = message.id
                     AND mapping.conversation_id = message.conversation_id
                     AND mapping.legacy_kind = 'FRIENDSHIP'
                    LEFT JOIN chat.legacy_v1_account_map sender
                      ON sender.account_id = message.sender_account_id
                    WHERE message.conversation_id = ?
                      AND (message.message_type <> 1 OR mapping.message_id IS NULL
                        OR mapping.legacy_content_type IS NULL OR sender.account_id IS NULL
                        OR mapping.legacy_message_id NOT BETWEEN 1 AND 2147483647))
                  OR EXISTS (
                    SELECT 1 FROM chat.message_recall_event recall
                    LEFT JOIN chat.legacy_v1_message_map mapping
                      ON mapping.message_id = recall.message_id
                     AND mapping.conversation_id = recall.conversation_id
                     AND mapping.legacy_kind = 'FRIENDSHIP'
                    WHERE recall.conversation_id = ? AND mapping.message_id IS NULL)
                  OR EXISTS (
                    SELECT recall.message_id FROM chat.message_recall_event recall
                    WHERE recall.conversation_id = ? GROUP BY recall.message_id HAVING count(*) > 1)
                """)) {
            for (int index = 1; index <= 6; index++) statement.setObject(index, conversationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("compatibility check returned no row");
                if (row.getBoolean(1)) {
                    throw new SQLException("V1 direct history compatibility state is incomplete");
                }
            }
        }
    }

    private static List<LegacyV1DirectHistoryMessage> readAfter(
            Connection connection, Target target, long after, int limit) throws SQLException {
        String order = "ORDER BY GREATEST(message.conversation_sequence, "
                + "COALESCE(recall.conversation_sequence, 0)) ASC LIMIT ?";
        String where = "AND GREATEST(message.conversation_sequence, "
                + "COALESCE(recall.conversation_sequence, 0)) > ? ";
        try (PreparedStatement statement = connection.prepareStatement(baseQuery() + where + order)) {
            statement.setLong(1, target.legacyFriendshipId());
            statement.setObject(2, target.conversationId());
            statement.setLong(3, after);
            statement.setInt(4, limit + 1);
            return rows(statement);
        }
    }

    private static List<LegacyV1DirectHistoryMessage> readLatest(
            Connection connection, Target target, long beforeEpochMillis, int limit)
            throws SQLException {
        String before = beforeEpochMillis > 0 ? "AND message.accepted_at < ? " : "";
        String sql = "SELECT * FROM (" + baseQuery() + before
                + "ORDER BY message.conversation_sequence DESC LIMIT ?) latest "
                + "ORDER BY sequence ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, target.legacyFriendshipId());
            statement.setObject(2, target.conversationId());
            int parameter = 3;
            if (beforeEpochMillis > 0) {
                statement.setObject(parameter++, OffsetDateTime.ofInstant(
                        Instant.ofEpochMilli(beforeEpochMillis), java.time.ZoneOffset.UTC));
            }
            statement.setInt(parameter, limit);
            return rows(statement);
        }
    }

    private static String baseQuery() {
        return """
                SELECT mapping.legacy_message_id, message.conversation_sequence AS sequence,
                       recall.conversation_sequence AS mutation_sequence,
                       GREATEST(message.conversation_sequence,
                           COALESCE(recall.conversation_sequence, 0)) AS sync_sequence,
                       message.client_message_id, account.username_key,
                       account.display_name, message.payload, mapping.legacy_content_type,
                       message.accepted_at
                FROM chat.message message
                JOIN chat.legacy_v1_message_map mapping
                  ON mapping.message_id = message.id
                 AND mapping.conversation_id = message.conversation_id
                 AND mapping.legacy_kind = 'FRIENDSHIP'
                 AND mapping.legacy_conversation_id = ?
                JOIN chat.account account ON account.id = message.sender_account_id
                JOIN chat.legacy_v1_account_map sender_map ON sender_map.account_id = account.id
                LEFT JOIN chat.message_recall_event recall
                  ON recall.conversation_id = message.conversation_id
                 AND recall.message_id = message.id
                WHERE message.conversation_id = ?
                """;
    }

    private static List<LegacyV1DirectHistoryMessage> rows(PreparedStatement statement)
            throws SQLException {
        List<LegacyV1DirectHistoryMessage> messages = new ArrayList<>();
        try (ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                Long mutation = row.getObject("mutation_sequence", Long.class);
                messages.add(new LegacyV1DirectHistoryMessage(
                        row.getLong("legacy_message_id"), row.getLong("sequence"), mutation,
                        row.getLong("sync_sequence"), row.getString("client_message_id"),
                        row.getString("username_key"), displayName(row),
                        decodeUtf8(row.getBytes("payload")), row.getString("legacy_content_type"),
                        mutation != null, row.getObject("accepted_at", OffsetDateTime.class)
                                .toInstant()));
            }
        }
        return messages;
    }

    private static String displayName(ResultSet row) throws SQLException {
        String display = row.getString("display_name");
        return display == null || display.isEmpty() ? row.getString("username_key") : display;
    }
    private static String decodeUtf8(byte[] bytes) throws SQLException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new SQLException("V1 direct history payload is not UTF-8", exception);
        }
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }
    private record Target(String targetUsername, UUID conversationId,
            long legacyFriendshipId, long lastSequence) { }
}
