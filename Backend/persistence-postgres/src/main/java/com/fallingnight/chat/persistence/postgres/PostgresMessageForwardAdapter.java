package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.messaging.MessageForwardCommand;
import com.fallingnight.chat.application.messaging.MessageForwardPort;
import com.fallingnight.chat.application.messaging.MessageForwardResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;

/** PostgreSQL authority for privacy-safe, one-target text forwarding. */
public final class PostgresMessageForwardAdapter implements MessageForwardPort {
    private static final int TEXT_UTF8 = 1;
    private final DataSource dataSource;
    private final Supplier<UUID> uuidSupplier;

    public PostgresMessageForwardAdapter(DataSource dataSource) {
        this(dataSource, UUID::randomUUID);
    }

    PostgresMessageForwardAdapter(DataSource dataSource, Supplier<UUID> uuidSupplier) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
    }

    @Override
    public MessageForwardResult forward(MessageForwardCommand command) {
        Objects.requireNonNull(command, "command");
        byte[] digest = requestDigest(command);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!authorized(connection, command.sourceConversationId(), command)
                        || !authorized(connection, command.targetConversationId(), command)) {
                    connection.rollback();
                    return MessageForwardResult.Rejected.NOT_AUTHORIZED;
                }
                Optional<ExistingForward> existing = findExisting(connection, command);
                if (existing.isPresent()) {
                    connection.rollback();
                    return existingResult(existing.orElseThrow(), digest);
                }
                if (!PostgresAccountBlockPolicy.allowsConversationWrite(
                        connection, command.targetConversationId(), command.actorAccountId())) {
                    connection.rollback();
                    return MessageForwardResult.Rejected.NOT_AUTHORIZED;
                }
                Optional<SourceText> source = sourceText(connection, command);
                if (source.isEmpty()) {
                    connection.rollback();
                    return MessageForwardResult.Rejected.NOT_AUTHORIZED;
                }
                if (source.orElseThrow().contentRevision()
                        != command.expectedSourceContentRevision()) {
                    connection.rollback();
                    return MessageForwardResult.Rejected.SOURCE_REVISION_CONFLICT;
                }
                long sequence = allocateSequence(connection, command.targetConversationId());
                UUID messageId = Objects.requireNonNull(uuidSupplier.get(), "messageId");
                Optional<StoredMessage> inserted = insert(
                        connection, messageId, sequence, command, source.orElseThrow(), digest);
                if (inserted.isPresent()) {
                    connection.commit();
                    return new MessageForwardResult.Accepted(inserted.orElseThrow(), false);
                }
                connection.rollback();
                return existingResult(findExistingAfterRace(command), digest);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new MessagePersistenceException("message forwarding failed", exception);
        } finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    private static boolean authorized(
            Connection connection, UUID conversationId, MessageForwardCommand command)
            throws SQLException {
        String sql = "SELECT 1 FROM chat.conversation_member cm "
                + "JOIN chat.conversation c ON c.id = cm.conversation_id "
                + "LEFT JOIN chat.group_lifecycle lifecycle ON lifecycle.conversation_id = c.id "
                + "JOIN chat.account a ON a.id = cm.account_id "
                + "JOIN chat.device d ON d.account_id = cm.account_id "
                + "WHERE cm.conversation_id = ? AND cm.account_id = ? AND cm.left_at IS NULL "
                + "AND a.disabled_at IS NULL AND d.id = ? AND d.revoked_at IS NULL "
                + "AND (c.kind = 'DIRECT' OR (lifecycle.conversation_id IS NOT NULL "
                + "AND lifecycle.closed_at IS NULL))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, conversationId);
            statement.setObject(2, command.actorAccountId());
            statement.setObject(3, command.actorDeviceId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Optional<SourceText> sourceText(
            Connection connection, MessageForwardCommand command) throws SQLException {
        String sql = "SELECT m.message_type,m.payload,m.content_revision FROM chat.message m "
                + "WHERE m.id = ? AND m.conversation_id = ? AND m.message_type = ? "
                + "AND m.deleted_at IS NULL AND NOT EXISTS (SELECT 1 "
                + "FROM chat.message_recall_event r WHERE r.conversation_id = m.conversation_id "
                + "AND r.message_id = m.id) FOR SHARE OF m";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, command.sourceMessageId());
            statement.setObject(2, command.sourceConversationId());
            statement.setInt(3, TEXT_UTF8);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(new SourceText(
                                result.getInt(1), result.getBytes(2), result.getInt(3)))
                        : Optional.empty();
            }
        }
    }

    private static long allocateSequence(Connection connection, UUID conversationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chat.conversation SET next_sequence = next_sequence + 1, "
                        + "updated_at = transaction_timestamp() WHERE id = ? "
                        + "RETURNING next_sequence - 1")) {
            statement.setObject(1, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("authorized target disappeared");
                return result.getLong(1);
            }
        }
    }

    private static Optional<StoredMessage> insert(
            Connection connection, UUID messageId, long sequence,
            MessageForwardCommand command, SourceText source, byte[] requestDigest)
            throws SQLException {
        try (PreparedStatement entry = connection.prepareStatement(
                "INSERT INTO chat.conversation_entry(conversation_id,conversation_sequence,"
                        + "entry_kind,occurred_at) VALUES (?,?,'MESSAGE',transaction_timestamp())")) {
            entry.setObject(1, command.targetConversationId());
            entry.setLong(2, sequence);
            entry.executeUpdate();
        }
        byte[] payloadHash = sha256(source.payload());
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat.message(id,conversation_id,conversation_sequence,"
                        + "sender_account_id,sender_device_id,client_message_id,message_type,"
                        + "payload,payload_sha256,forwarded) VALUES (?,?,?,?,?,?,?,?,?,TRUE) "
                        + "ON CONFLICT (sender_account_id,client_message_id) DO NOTHING "
                        + "RETURNING accepted_at")) {
            statement.setObject(1, messageId);
            statement.setObject(2, command.targetConversationId());
            statement.setLong(3, sequence);
            statement.setObject(4, command.actorAccountId());
            statement.setObject(5, command.actorDeviceId());
            statement.setString(6, command.clientMessageId());
            statement.setInt(7, source.messageType());
            statement.setBytes(8, source.payload());
            statement.setBytes(9, payloadHash);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                Instant acceptedAt = result.getObject(1, OffsetDateTime.class).toInstant();
                try (PreparedStatement request = connection.prepareStatement(
                        "INSERT INTO chat.message_forward_request("
                                + "destination_message_id,request_sha256) VALUES (?,?)")) {
                    request.setObject(1, messageId);
                    request.setBytes(2, requestDigest);
                    request.executeUpdate();
                }
                return Optional.of(new StoredMessage(
                        messageId, command.targetConversationId(), sequence,
                        command.actorAccountId(), command.actorDeviceId(),
                        command.clientMessageId(), source.messageType(), source.payload(),
                        acceptedAt, Optional.empty(), 0, Optional.empty(), List.of(), true));
            }
        } finally {
            Arrays.fill(payloadHash, (byte) 0);
        }
    }

    private static Optional<ExistingForward> findExisting(
            Connection connection, MessageForwardCommand command) throws SQLException {
        String sql = "SELECT m.id,m.conversation_id,m.conversation_sequence,m.sender_device_id,"
                + "m.client_message_id,m.message_type,m.payload,m.accepted_at,m.content_revision,"
                + "m.edited_at,m.forwarded,f.request_sha256 FROM chat.message m "
                + "LEFT JOIN chat.message_forward_request f ON f.destination_message_id = m.id "
                + "WHERE m.sender_account_id = ? AND m.client_message_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, command.actorAccountId());
            statement.setString(2, command.clientMessageId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                StoredMessage message = new StoredMessage(
                        result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                        result.getLong(3), command.actorAccountId(),
                        result.getObject(4, UUID.class), result.getString(5), result.getInt(6),
                        result.getBytes(7), result.getObject(8, OffsetDateTime.class).toInstant(),
                        Optional.empty(), result.getInt(9),
                        Optional.ofNullable(result.getObject(10, OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant),
                        List.of(), result.getBoolean(11));
                return Optional.of(new ExistingForward(message, result.getBytes(12)));
            }
        }
    }

    private ExistingForward findExistingAfterRace(MessageForwardCommand command) {
        try (Connection connection = dataSource.getConnection()) {
            return findExisting(connection, command).orElseThrow(() ->
                    new IllegalStateException("forward idempotency row disappeared"));
        } catch (SQLException exception) {
            throw new MessagePersistenceException("forward idempotency read failed", exception);
        }
    }

    private static MessageForwardResult existingResult(
            ExistingForward existing, byte[] requestDigest) {
        return existing.message().forwarded()
                        && existing.requestDigest() != null
                        && MessageDigest.isEqual(existing.requestDigest(), requestDigest)
                ? new MessageForwardResult.Accepted(existing.message(), true)
                : MessageForwardResult.Rejected.IDEMPOTENCY_CONFLICT;
    }

    private static byte[] requestDigest(MessageForwardCommand command) {
        ByteBuffer bytes = ByteBuffer.allocate(16 + 16 + Integer.BYTES + 16);
        putUuid(bytes, command.sourceConversationId());
        putUuid(bytes, command.sourceMessageId());
        bytes.putInt(command.expectedSourceContentRevision());
        putUuid(bytes, command.targetConversationId());
        return sha256(bytes.array());
    }

    private static void putUuid(ByteBuffer bytes, UUID value) {
        bytes.putLong(value.getMostSignificantBits());
        bytes.putLong(value.getLeastSignificantBits());
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

    private record SourceText(int messageType, byte[] payload, int contentRevision) {}
    private record ExistingForward(StoredMessage message, byte[] requestDigest) {}
}
