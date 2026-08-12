package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.messaging.MessageHistoryPort;
import com.fallingnight.chat.application.messaging.ConversationEntryHistoryPort;
import com.fallingnight.chat.application.messaging.ConversationEntryHistoryResult;
import com.fallingnight.chat.application.messaging.ConversationHistoryEntry;
import com.fallingnight.chat.application.messaging.MessageHistoryQuery;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.MessageSubmission;
import com.fallingnight.chat.application.messaging.MessageSubmissionPort;
import com.fallingnight.chat.application.messaging.MessageSubmissionResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;

/** Atomic message append/idempotency and active-member sequence history adapter. */
public final class PostgresMessageAdapter implements MessageSubmissionPort, MessageHistoryPort,
        ConversationEntryHistoryPort {
    private final DataSource dataSource;
    private final Supplier<UUID> uuidSupplier;

    public PostgresMessageAdapter(DataSource dataSource) {
        this(dataSource, UUID::randomUUID);
    }

    PostgresMessageAdapter(DataSource dataSource, Supplier<UUID> uuidSupplier) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
    }

    @Override
    public MessageSubmissionResult submit(MessageSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        byte[] payload = submission.payload();
        byte[] payloadHash = sha256(payload);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!authorizedSender(connection, submission)) {
                    connection.rollback();
                    return MessageSubmissionResult.Rejected.NOT_AUTHORIZED;
                }
                Optional<ExistingMessage> existing = findExisting(connection, submission);
                if (existing.isPresent()) {
                    connection.rollback();
                    return existingResult(existing.orElseThrow(), submission, payload, payloadHash);
                }
                long sequence = allocateSequence(connection, submission.conversationId());
                UUID messageId = Objects.requireNonNull(uuidSupplier.get(), "messageId");
                Optional<Instant> insertedAt = insert(
                        connection,
                        messageId,
                        sequence,
                        submission,
                        payload,
                        payloadHash);
                if (insertedAt.isPresent()) {
                    connection.commit();
                    return new MessageSubmissionResult.Accepted(
                            messageId, sequence, insertedAt.orElseThrow(), false);
                }
                connection.rollback();
                ExistingMessage raced = findExistingAfterRace(submission);
                return existingResult(raced, submission, payload, payloadHash);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new MessagePersistenceException("message submission failed", exception);
        } finally {
            Arrays.fill(payloadHash, (byte) 0);
        }
    }

    @Override
    public MessageHistoryResult readAfter(MessageHistoryQuery query) {
        ConversationEntryHistoryResult result = readEntriesAfter(query);
        if (result == ConversationEntryHistoryResult.Rejected.NOT_AUTHORIZED) {
            return MessageHistoryResult.Rejected.NOT_AUTHORIZED;
        }
        ConversationEntryHistoryResult.Page page = (ConversationEntryHistoryResult.Page) result;
        List<StoredMessage> messages = page.entries().stream()
                .filter(ConversationHistoryEntry.Message.class::isInstance)
                .map(ConversationHistoryEntry.Message.class::cast)
                .map(ConversationHistoryEntry.Message::value)
                .toList();
        return new MessageHistoryResult.Page(
                messages, page.entries(), page.nextSequence(), page.latestSequence(),
                page.hasMore());
    }

    @Override
    public ConversationEntryHistoryResult readEntriesAfter(MessageHistoryQuery query) {
        Objects.requireNonNull(query, "query");
        try (Connection connection = dataSource.getConnection()) {
            Optional<Long> latest = authorizedLatestSequence(connection, query);
            if (latest.isEmpty()) {
                return ConversationEntryHistoryResult.Rejected.NOT_AUTHORIZED;
            }
            List<ConversationHistoryEntry> entries = readEntries(connection, query);
            boolean hasMore = entries.size() > query.limit();
            if (hasMore) entries.removeLast();
            long next = entries.isEmpty()
                    ? query.afterSequence()
                    : entries.getLast().conversationSequence();
            return new ConversationEntryHistoryResult.Page(
                    entries, next, latest.orElseThrow(), hasMore);
        } catch (SQLException exception) {
            throw new MessagePersistenceException(
                    "conversation entry history read failed", exception);
        }
    }

    private static List<ConversationHistoryEntry> readEntries(
            Connection connection, MessageHistoryQuery query) throws SQLException {
        String sql = """
                SELECT ce.conversation_sequence, ce.entry_kind, ce.occurred_at,
                       m.id, m.sender_account_id, m.sender_device_id,
                       m.client_message_id, m.message_type, m.payload, m.accepted_at,
                       r.message_id, r.actor_account_id, r.source,
                       d.actor_account_id, d.source, d.mode, d.client_operation_id,
                       CASE WHEN d.source = 'V1_IMPORT' THEN ARRAY(
                           SELECT lm.message_id
                           FROM jsonb_array_elements_text(d.message_ids)
                                WITH ORDINALITY AS source_id(value, position)
                           JOIN chat.legacy_v1_message_map lm
                             ON lm.legacy_kind = 'ROOM'
                            AND lm.legacy_conversation_id = ldm.legacy_room_id
                            AND lm.legacy_message_id = source_id.value::bigint
                           ORDER BY source_id.position)
                       WHEN d.source = 'V2' THEN ARRAY(
                           SELECT source_id.value::uuid
                           FROM jsonb_array_elements_text(d.message_ids)
                                WITH ORDINALITY AS source_id(value, position)
                           ORDER BY source_id.position)
                       ELSE ARRAY[]::uuid[] END AS deletion_message_ids,
                       d.cutoff_epoch_ms, d.deleted_count,
                       d.operator_name_snapshot
                FROM chat.conversation_entry ce
                LEFT JOIN chat.message m
                  ON ce.entry_kind = 'MESSAGE'
                 AND m.conversation_id = ce.conversation_id
                 AND m.conversation_sequence = ce.conversation_sequence
                LEFT JOIN chat.message_recall_event r
                  ON ce.entry_kind = 'MESSAGE_RECALLED'
                 AND r.conversation_id = ce.conversation_id
                 AND r.conversation_sequence = ce.conversation_sequence
                LEFT JOIN chat.messages_deleted_event d
                  ON ce.entry_kind = 'MESSAGES_DELETED'
                 AND d.conversation_id = ce.conversation_id
                 AND d.conversation_sequence = ce.conversation_sequence
                LEFT JOIN chat.legacy_v1_deletion_event_map ldm
                  ON d.source = 'V1_IMPORT'
                 AND ldm.conversation_id = ce.conversation_id
                 AND ldm.conversation_sequence = ce.conversation_sequence
                WHERE ce.conversation_id = ? AND ce.conversation_sequence > ?
                ORDER BY ce.conversation_sequence ASC LIMIT ?
                """;
        List<ConversationHistoryEntry> entries = new ArrayList<>(query.limit() + 1);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, query.conversationId());
            statement.setLong(2, query.afterSequence());
            statement.setInt(3, query.limit() + 1);
            statement.setFetchSize(query.limit() + 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    entries.add(readEntry(result, query.conversationId()));
                }
            }
        }
        return entries;
    }

    private static ConversationHistoryEntry readEntry(
            ResultSet result, UUID conversationId) throws SQLException {
        long sequence = result.getLong(1);
        return switch (result.getString(2)) {
            case "MESSAGE" -> new ConversationHistoryEntry.Message(new StoredMessage(
                    result.getObject(4, UUID.class), conversationId, sequence,
                    result.getObject(5, UUID.class), result.getObject(6, UUID.class),
                    result.getString(7), result.getInt(8), result.getBytes(9),
                    result.getObject(10, OffsetDateTime.class).toInstant()));
            case "MESSAGE_RECALLED" -> new ConversationHistoryEntry.Recall(
                    conversationId, sequence, result.getObject(11, UUID.class),
                    result.getObject(12, UUID.class), result.getString(13),
                    Optional.ofNullable(result.getObject(3, OffsetDateTime.class))
                            .map(OffsetDateTime::toInstant));
            case "MESSAGES_DELETED" -> new ConversationHistoryEntry.Deletion(
                    conversationId, sequence, result.getObject(14, UUID.class),
                    result.getString(15), result.getString(16), result.getString(17),
                    uuidList(result.getArray(18)), result.getLong(19), result.getInt(20),
                    result.getString(21),
                    result.getObject(3, OffsetDateTime.class).toInstant());
            default -> throw new SQLException("unsupported conversation entry kind");
        };
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

    private static boolean authorizedSender(Connection connection, MessageSubmission submission)
            throws SQLException {
        String sql = "SELECT 1 FROM chat.conversation_member cm "
                + "JOIN chat.conversation c ON c.id = cm.conversation_id "
                + "LEFT JOIN chat.group_lifecycle lifecycle ON lifecycle.conversation_id = c.id "
                + "JOIN chat.device d ON d.account_id = cm.account_id "
                + "JOIN chat.account a ON a.id = cm.account_id "
                + "WHERE cm.conversation_id = ? AND cm.account_id = ? AND cm.left_at IS NULL "
                + "AND d.id = ? AND d.revoked_at IS NULL AND a.disabled_at IS NULL "
                + "AND (c.kind = 'DIRECT' OR (lifecycle.conversation_id IS NOT NULL "
                + "AND lifecycle.closed_at IS NULL))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, submission.conversationId());
            statement.setObject(2, submission.senderAccountId());
            statement.setObject(3, submission.senderDeviceId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Optional<Long> authorizedLatestSequence(
            Connection connection, MessageHistoryQuery query) throws SQLException {
        String sql = "SELECT c.next_sequence - 1 FROM chat.conversation c "
                + "JOIN chat.conversation_member cm ON cm.conversation_id = c.id "
                + "LEFT JOIN chat.group_lifecycle lifecycle ON lifecycle.conversation_id = c.id "
                + "JOIN chat.account a ON a.id = cm.account_id "
                + "WHERE c.id = ? AND cm.account_id = ? AND cm.left_at IS NULL "
                + "AND a.disabled_at IS NULL "
                + "AND (c.kind = 'DIRECT' OR (lifecycle.conversation_id IS NOT NULL "
                + "AND lifecycle.closed_at IS NULL))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, query.conversationId());
            statement.setObject(2, query.accountId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getLong(1)) : Optional.empty();
            }
        }
    }

    private static Optional<ExistingMessage> findExisting(
            Connection connection, MessageSubmission submission) throws SQLException {
        String sql = "SELECT id, conversation_id, conversation_sequence, sender_device_id, "
                + "message_type, payload, payload_sha256, accepted_at "
                + "FROM chat.message WHERE sender_account_id = ? AND client_message_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, submission.senderAccountId());
            statement.setString(2, submission.clientMessageId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readExisting(result)) : Optional.empty();
            }
        }
    }

    private ExistingMessage findExistingAfterRace(MessageSubmission submission) {
        try (Connection connection = dataSource.getConnection()) {
            return findExisting(connection, submission).orElseThrow(
                    () -> new MessagePersistenceException(
                            "idempotency conflict row disappeared",
                            new IllegalStateException("missing durable conflict row")));
        } catch (SQLException exception) {
            throw new MessagePersistenceException("idempotency conflict read failed", exception);
        }
    }

    private static long allocateSequence(Connection connection, UUID conversationId)
            throws SQLException {
        String sql = "UPDATE chat.conversation SET next_sequence = next_sequence + 1, "
                + "updated_at = transaction_timestamp() WHERE id = ? "
                + "RETURNING next_sequence - 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("authorized conversation disappeared");
                }
                return result.getLong(1);
            }
        }
    }

    private static Optional<Instant> insert(
            Connection connection,
            UUID messageId,
            long sequence,
            MessageSubmission submission,
            byte[] payload,
            byte[] payloadHash) throws SQLException {
        insertConversationEntry(connection, submission.conversationId(), sequence);
        String sql = "INSERT INTO chat.message(id, conversation_id, conversation_sequence, "
                + "sender_account_id, sender_device_id, client_message_id, message_type, "
                + "payload, payload_sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (sender_account_id, client_message_id) DO NOTHING "
                + "RETURNING accepted_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, messageId);
            statement.setObject(2, submission.conversationId());
            statement.setLong(3, sequence);
            statement.setObject(4, submission.senderAccountId());
            statement.setObject(5, submission.senderDeviceId());
            statement.setString(6, submission.clientMessageId());
            statement.setInt(7, submission.messageType());
            statement.setBytes(8, payload);
            statement.setBytes(9, payloadHash);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(result.getObject(1, OffsetDateTime.class).toInstant())
                        : Optional.empty();
            }
        }
    }

    private static void insertConversationEntry(
            Connection connection, UUID conversationId, long sequence) throws SQLException {
        String sql = "INSERT INTO chat.conversation_entry("
                + "conversation_id, conversation_sequence, entry_kind, occurred_at) "
                + "VALUES (?, ?, 'MESSAGE', transaction_timestamp())";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, conversationId);
            statement.setLong(2, sequence);
            statement.executeUpdate();
        }
    }

    private static MessageSubmissionResult existingResult(
            ExistingMessage existing,
            MessageSubmission submission,
            byte[] payload,
            byte[] payloadHash) {
        boolean exact = existing.conversationId().equals(submission.conversationId())
                && existing.senderDeviceId().equals(submission.senderDeviceId())
                && existing.messageType() == submission.messageType()
                && MessageDigest.isEqual(existing.payloadHash(), payloadHash)
                && Arrays.equals(existing.payload(), payload);
        return exact
                ? new MessageSubmissionResult.Accepted(
                        existing.messageId(),
                        existing.sequence(),
                        existing.acceptedAt(),
                        true)
                : MessageSubmissionResult.Rejected.IDEMPOTENCY_CONFLICT;
    }

    private static ExistingMessage readExisting(ResultSet result) throws SQLException {
        return new ExistingMessage(
                result.getObject(1, UUID.class),
                result.getObject(2, UUID.class),
                result.getLong(3),
                result.getObject(4, UUID.class),
                result.getInt(5),
                result.getBytes(6),
                result.getBytes(7),
                result.getObject(8, OffsetDateTime.class).toInstant());
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record ExistingMessage(
            UUID messageId,
            UUID conversationId,
            long sequence,
            UUID senderDeviceId,
            int messageType,
            byte[] payload,
            byte[] payloadHash,
            Instant acceptedAt) {}
}
