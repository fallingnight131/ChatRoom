package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.messaging.MessageHistoryPort;
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
public final class PostgresMessageAdapter implements MessageSubmissionPort, MessageHistoryPort {
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
        Objects.requireNonNull(query, "query");
        String sql = "SELECT id, conversation_sequence, sender_account_id, sender_device_id, "
                + "client_message_id, message_type, payload, accepted_at "
                + "FROM chat.message WHERE conversation_id = ? "
                + "AND conversation_sequence > ? AND deleted_at IS NULL "
                + "ORDER BY conversation_sequence ASC LIMIT ?";
        try (Connection connection = dataSource.getConnection()) {
            Optional<Long> latest = authorizedLatestSequence(connection, query);
            if (latest.isEmpty()) {
                return MessageHistoryResult.Rejected.NOT_AUTHORIZED;
            }
            List<StoredMessage> messages = new ArrayList<>(query.limit());
            boolean hasMore;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, query.conversationId());
                statement.setLong(2, query.afterSequence());
                statement.setInt(3, query.limit() + 1);
                statement.setFetchSize(query.limit() + 1);
                try (ResultSet result = statement.executeQuery()) {
                    while (messages.size() <= query.limit() && result.next()) {
                        messages.add(readMessage(result, query.conversationId()));
                    }
                }
            }
            hasMore = messages.size() > query.limit();
            if (hasMore) {
                messages.removeLast();
            }
            long next = messages.isEmpty()
                    ? query.afterSequence()
                    : messages.getLast().conversationSequence();
            return new MessageHistoryResult.Page(messages, next, latest.orElseThrow(), hasMore);
        } catch (SQLException exception) {
            throw new MessagePersistenceException("message history read failed", exception);
        }
    }

    private static boolean authorizedSender(Connection connection, MessageSubmission submission)
            throws SQLException {
        String sql = "SELECT 1 FROM chat.conversation_member cm "
                + "JOIN chat.device d ON d.account_id = cm.account_id "
                + "JOIN chat.account a ON a.id = cm.account_id "
                + "WHERE cm.conversation_id = ? AND cm.account_id = ? AND cm.left_at IS NULL "
                + "AND d.id = ? AND d.revoked_at IS NULL AND a.disabled_at IS NULL";
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
                + "JOIN chat.account a ON a.id = cm.account_id "
                + "WHERE c.id = ? AND cm.account_id = ? AND cm.left_at IS NULL "
                + "AND a.disabled_at IS NULL";
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

    private static StoredMessage readMessage(ResultSet result, UUID conversationId)
            throws SQLException {
        return new StoredMessage(
                result.getObject(1, UUID.class),
                conversationId,
                result.getLong(2),
                result.getObject(3, UUID.class),
                result.getObject(4, UUID.class),
                result.getString(5),
                result.getInt(6),
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
