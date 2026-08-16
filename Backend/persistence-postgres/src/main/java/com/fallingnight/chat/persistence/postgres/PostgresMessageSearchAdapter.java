package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.messaging.MessageMention;
import com.fallingnight.chat.application.messaging.MessageReplyReference;
import com.fallingnight.chat.application.messaging.MessageSearchPage;
import com.fallingnight.chat.application.messaging.MessageSearchPort;
import com.fallingnight.chat.application.messaging.MessageSearchQuery;
import com.fallingnight.chat.application.messaging.MessageSearchResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Current-text literal search with active-membership authorization. */
public final class PostgresMessageSearchAdapter implements MessageSearchPort {
    private static final String AUTHORIZED = "SELECT 1 FROM chat.conversation c "
            + "JOIN chat.conversation_member member ON member.conversation_id = c.id "
            + "JOIN chat.account account ON account.id = member.account_id "
            + "LEFT JOIN chat.group_lifecycle lifecycle ON lifecycle.conversation_id = c.id "
            + "WHERE c.id = ? AND member.account_id = ? AND member.left_at IS NULL "
            + "AND account.disabled_at IS NULL "
            + "AND (c.kind = 'DIRECT' OR (lifecycle.conversation_id IS NOT NULL "
            + "AND lifecycle.closed_at IS NULL))";

    private static final String SEARCH = """
            SELECT m.id, m.conversation_sequence, m.sender_account_id,
                   m.sender_device_id, m.client_message_id, m.message_type,
                   m.payload, m.accepted_at,
                   reply.target_message_id, reply.target_conversation_sequence,
                   reply.target_sender_account_id,
                   m.content_revision, m.edited_at,
                   ARRAY(SELECT mention.target_account_id
                       FROM chat.message_mention mention
                       WHERE mention.conversation_id = m.conversation_id
                         AND mention.message_id = m.id
                       ORDER BY mention.mention_ordinal),
                   ARRAY(SELECT mention.start_utf8_byte
                       FROM chat.message_mention mention
                       WHERE mention.conversation_id = m.conversation_id
                         AND mention.message_id = m.id
                       ORDER BY mention.mention_ordinal),
                   ARRAY(SELECT mention.length_utf8_bytes
                       FROM chat.message_mention mention
                       WHERE mention.conversation_id = m.conversation_id
                         AND mention.message_id = m.id
                       ORDER BY mention.mention_ordinal),
                   m.forwarded
            FROM chat.message m
            LEFT JOIN chat.message_reply_reference reply ON reply.message_id = m.id
            WHERE m.conversation_id = ?
              AND (? = 0 OR m.conversation_sequence < ?)
              AND m.message_type = 1
              AND m.deleted_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM chat.message_recall_event recall
                  WHERE recall.conversation_id = m.conversation_id
                    AND recall.message_id = m.id)
              AND position(lower(?) in lower(convert_from(m.payload, 'UTF8'))) > 0
            ORDER BY m.conversation_sequence DESC
            LIMIT ?
            """;

    private final DataSource dataSource;

    public PostgresMessageSearchAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public MessageSearchResult search(MessageSearchQuery query) {
        Objects.requireNonNull(query, "query");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                if (!authorized(connection, query)) {
                    connection.rollback();
                    return MessageSearchResult.Rejected.NOT_AUTHORIZED;
                }
                List<StoredMessage> hits = readHits(connection, query);
                connection.commit();
                boolean hasMore = hits.size() > query.limit();
                if (hasMore) hits.removeLast();
                long next = hits.isEmpty() ? 0 : hits.getLast().conversationSequence();
                return new MessageSearchResult.Found(new MessageSearchPage(
                        query.conversationId(), hits, next, hasMore));
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new MessagePersistenceException("message search failed", exception);
        }
    }

    private static boolean authorized(Connection connection, MessageSearchQuery query)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(AUTHORIZED)) {
            statement.setObject(1, query.conversationId());
            statement.setObject(2, query.accountId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static List<StoredMessage> readHits(
            Connection connection, MessageSearchQuery query) throws SQLException {
        List<StoredMessage> hits = new ArrayList<>(query.limit() + 1);
        try (PreparedStatement statement = connection.prepareStatement(SEARCH)) {
            statement.setObject(1, query.conversationId());
            statement.setLong(2, query.beforeSequence());
            statement.setLong(3, query.beforeSequence());
            statement.setString(4, query.literalQuery());
            statement.setInt(5, query.limit() + 1);
            statement.setFetchSize(query.limit() + 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    hits.add(new StoredMessage(
                            result.getObject(1, UUID.class), query.conversationId(),
                            result.getLong(2), result.getObject(3, UUID.class),
                            result.getObject(4, UUID.class), result.getString(5),
                            result.getInt(6), result.getBytes(7),
                            result.getObject(8, OffsetDateTime.class).toInstant(),
                            readReply(result), result.getInt(12),
                            Optional.ofNullable(result.getObject(13, OffsetDateTime.class))
                                    .map(OffsetDateTime::toInstant),
                            readMentions(result), result.getBoolean(17)));
                }
            }
        }
        return hits;
    }

    private static Optional<MessageReplyReference> readReply(ResultSet result)
            throws SQLException {
        UUID target = result.getObject(9, UUID.class);
        if (target == null) return Optional.empty();
        return Optional.of(new MessageReplyReference(
                target, result.getLong(10), result.getObject(11, UUID.class)));
    }

    private static List<MessageMention> readMentions(ResultSet result) throws SQLException {
        List<UUID> targets = uuidList(result.getArray(14));
        List<Integer> starts = integerList(result.getArray(15));
        List<Integer> lengths = integerList(result.getArray(16));
        if (targets.size() != starts.size() || targets.size() != lengths.size()) {
            throw new SQLException("mention projection cardinality differs");
        }
        List<MessageMention> mentions = new ArrayList<>(targets.size());
        for (int index = 0; index < targets.size(); index++) {
            mentions.add(new MessageMention(
                    targets.get(index), starts.get(index), lengths.get(index)));
        }
        return List.copyOf(mentions);
    }

    private static List<UUID> uuidList(java.sql.Array array) throws SQLException {
        if (array == null) return List.of();
        try {
            Object[] values = (Object[]) array.getArray();
            List<UUID> result = new ArrayList<>(values.length);
            for (Object value : values) result.add((UUID) value);
            return List.copyOf(result);
        } finally {
            array.free();
        }
    }

    private static List<Integer> integerList(java.sql.Array array) throws SQLException {
        if (array == null) return List.of();
        try {
            Object[] values = (Object[]) array.getArray();
            List<Integer> result = new ArrayList<>(values.length);
            for (Object value : values) result.add(((Number) value).intValue());
            return List.copyOf(result);
        } finally {
            array.free();
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
