package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.messaging.MessageReactionCommand;
import com.fallingnight.chat.application.messaging.MessageReactionKind;
import com.fallingnight.chat.application.messaging.MessageReactionPort;
import com.fallingnight.chat.application.messaging.MessageReactionResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import javax.sql.DataSource;

/** Atomic reaction idempotency, active-state, and conversation-event adapter. */
public final class PostgresMessageReactionAdapter implements MessageReactionPort {
    private final DataSource dataSource;

    public PostgresMessageReactionAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public MessageReactionResult set(MessageReactionCommand command) {
        Objects.requireNonNull(command, "command");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!authorizedActor(connection, command)) {
                    connection.rollback();
                    return MessageReactionResult.Rejected.NOT_AUTHORIZED;
                }
                ExistingOperation existing = findExisting(connection, command);
                if (existing != null) {
                    connection.rollback();
                    return existingResult(existing, command);
                }
                if (!lockAuthorizedTarget(connection, command)) {
                    connection.rollback();
                    return MessageReactionResult.Rejected.NOT_AUTHORIZED;
                }
                boolean currentlyActive = currentlyActive(connection, command);
                boolean changed = currentlyActive != command.active();
                Allocation allocation = changed
                        ? allocateSequence(connection, command.conversationId())
                        : new Allocation(0, transactionTime(connection));
                if (changed) {
                    updateState(connection, command, allocation);
                    insertEntry(connection, command, allocation);
                }
                if (!insertOperation(connection, command, changed, allocation)) {
                    connection.rollback();
                    return existingAfterRace(command);
                }
                if (changed) insertEvent(connection, command, allocation);
                connection.commit();
                return applied(command, changed, allocation, false);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new MessagePersistenceException("message reaction failed", exception);
        }
    }

    private static boolean authorizedActor(Connection connection, MessageReactionCommand command)
            throws SQLException {
        String sql = "SELECT 1 FROM chat.conversation c "
                + "JOIN chat.conversation_member cm ON cm.conversation_id = c.id "
                + "JOIN chat.device d ON d.account_id = cm.account_id "
                + "JOIN chat.account a ON a.id = cm.account_id "
                + "WHERE c.id = ? AND cm.account_id = ? AND cm.left_at IS NULL "
                + "AND d.id = ? AND d.revoked_at IS NULL AND a.disabled_at IS NULL "
                + "AND (c.kind = 'DIRECT' OR EXISTS (SELECT 1 FROM chat.group_lifecycle gl "
                + "WHERE gl.conversation_id = c.id AND gl.closed_at IS NULL))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, command.conversationId());
            statement.setObject(2, command.actorAccountId());
            statement.setObject(3, command.actorDeviceId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean lockAuthorizedTarget(
            Connection connection, MessageReactionCommand command) throws SQLException {
        String sql = "SELECT 1 FROM chat.conversation c "
                + "JOIN chat.conversation_member cm ON cm.conversation_id = c.id "
                + "JOIN chat.device d ON d.account_id = cm.account_id "
                + "JOIN chat.account a ON a.id = cm.account_id "
                + "JOIN chat.message m ON m.conversation_id = c.id AND m.id = ? "
                + "WHERE c.id = ? AND cm.account_id = ? AND cm.left_at IS NULL "
                + "AND d.id = ? AND d.revoked_at IS NULL AND a.disabled_at IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM chat.message_recall_event recall "
                + "WHERE recall.conversation_id = c.id AND recall.message_id = m.id) "
                + "AND (c.kind = 'DIRECT' OR EXISTS (SELECT 1 FROM chat.group_lifecycle gl "
                + "WHERE gl.conversation_id = c.id AND gl.closed_at IS NULL)) "
                + "FOR UPDATE OF c, m";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, command.messageId());
            statement.setObject(2, command.conversationId());
            statement.setObject(3, command.actorAccountId());
            statement.setObject(4, command.actorDeviceId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static ExistingOperation findExisting(
            Connection connection, MessageReactionCommand command) throws SQLException {
        String sql = "SELECT conversation_id, actor_device_id, message_id, reaction, "
                + "desired_active, changed, conversation_sequence, occurred_at "
                + "FROM chat.message_reaction_operation "
                + "WHERE actor_account_id = ? AND client_operation_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, command.actorAccountId());
            statement.setString(2, command.clientOperationId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new ExistingOperation(
                        result.getObject(1, java.util.UUID.class),
                        result.getObject(2, java.util.UUID.class),
                        result.getObject(3, java.util.UUID.class),
                        MessageReactionKind.valueOf(result.getString(4)),
                        result.getBoolean(5), result.getBoolean(6),
                        result.getLong(7),
                        result.getObject(8, OffsetDateTime.class).toInstant()) : null;
            }
        }
    }

    private MessageReactionResult existingAfterRace(MessageReactionCommand command) {
        try (Connection connection = dataSource.getConnection()) {
            ExistingOperation existing = findExisting(connection, command);
            if (existing == null) {
                throw new MessagePersistenceException(
                        "reaction idempotency conflict row disappeared",
                        new IllegalStateException("missing durable conflict row"));
            }
            return existingResult(existing, command);
        } catch (SQLException exception) {
            throw new MessagePersistenceException(
                    "reaction idempotency conflict read failed", exception);
        }
    }

    private static MessageReactionResult existingResult(
            ExistingOperation existing, MessageReactionCommand command) {
        boolean exact = existing.conversationId().equals(command.conversationId())
                && existing.actorDeviceId().equals(command.actorDeviceId())
                && existing.messageId().equals(command.messageId())
                && existing.reaction() == command.reaction()
                && existing.active() == command.active();
        return exact
                ? new MessageReactionResult.Applied(
                        existing.conversationId(), existing.messageId(),
                        command.actorAccountId(), existing.reaction(), existing.active(),
                        command.clientOperationId(), existing.changed(), existing.sequence(),
                        existing.occurredAt(), true)
                : MessageReactionResult.Rejected.IDEMPOTENCY_CONFLICT;
    }

    private static boolean currentlyActive(
            Connection connection, MessageReactionCommand command) throws SQLException {
        String sql = "SELECT 1 FROM chat.message_reaction WHERE message_id = ? "
                + "AND actor_account_id = ? AND reaction = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, command.messageId());
            statement.setObject(2, command.actorAccountId());
            statement.setString(3, command.reaction().name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Allocation allocateSequence(Connection connection, java.util.UUID conversation)
            throws SQLException {
        String sql = "UPDATE chat.conversation SET next_sequence = next_sequence + 1, "
                + "updated_at = transaction_timestamp() WHERE id = ? "
                + "RETURNING next_sequence - 1, transaction_timestamp()";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, conversation);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("reaction conversation disappeared");
                return new Allocation(result.getLong(1),
                        result.getObject(2, OffsetDateTime.class).toInstant());
            }
        }
    }

    private static Instant transactionTime(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT transaction_timestamp()");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("transaction time unavailable");
            return result.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    private static void updateState(Connection connection, MessageReactionCommand command,
            Allocation allocation) throws SQLException {
        if (command.active()) {
            String sql = "INSERT INTO chat.message_reaction(conversation_id, message_id, "
                    + "actor_account_id, reaction, last_conversation_sequence, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, command.conversationId());
                statement.setObject(2, command.messageId());
                statement.setObject(3, command.actorAccountId());
                statement.setString(4, command.reaction().name());
                statement.setLong(5, allocation.sequence());
                statement.setObject(6, OffsetDateTime.ofInstant(
                        allocation.occurredAt(), java.time.ZoneOffset.UTC));
                statement.executeUpdate();
            }
        } else {
            String sql = "DELETE FROM chat.message_reaction WHERE message_id = ? "
                    + "AND actor_account_id = ? AND reaction = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, command.messageId());
                statement.setObject(2, command.actorAccountId());
                statement.setString(3, command.reaction().name());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("reaction state changed while locked");
                }
            }
        }
    }

    private static void insertEntry(Connection connection, MessageReactionCommand command,
            Allocation allocation) throws SQLException {
        String sql = "INSERT INTO chat.conversation_entry(conversation_id, "
                + "conversation_sequence, entry_kind, occurred_at) "
                + "VALUES (?, ?, 'MESSAGE_REACTION_CHANGED', ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, command.conversationId());
            statement.setLong(2, allocation.sequence());
            statement.setObject(3, OffsetDateTime.ofInstant(
                    allocation.occurredAt(), java.time.ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static boolean insertOperation(Connection connection, MessageReactionCommand command,
            boolean changed, Allocation allocation) throws SQLException {
        String sql = "INSERT INTO chat.message_reaction_operation(actor_account_id, "
                + "client_operation_id, conversation_id, actor_device_id, message_id, reaction, "
                + "desired_active, changed, conversation_sequence, occurred_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (actor_account_id, client_operation_id) DO NOTHING";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, command.actorAccountId());
            statement.setString(2, command.clientOperationId());
            statement.setObject(3, command.conversationId());
            statement.setObject(4, command.actorDeviceId());
            statement.setObject(5, command.messageId());
            statement.setString(6, command.reaction().name());
            statement.setBoolean(7, command.active());
            statement.setBoolean(8, changed);
            if (changed) statement.setLong(9, allocation.sequence());
            else statement.setNull(9, java.sql.Types.BIGINT);
            statement.setObject(10, OffsetDateTime.ofInstant(
                    allocation.occurredAt(), java.time.ZoneOffset.UTC));
            return statement.executeUpdate() == 1;
        }
    }

    private static void insertEvent(Connection connection, MessageReactionCommand command,
            Allocation allocation) throws SQLException {
        String sql = "INSERT INTO chat.message_reaction_event(conversation_id, "
                + "conversation_sequence, message_id, actor_account_id, actor_device_id, "
                + "reaction, active, client_operation_id, occurred_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, command.conversationId());
            statement.setLong(2, allocation.sequence());
            statement.setObject(3, command.messageId());
            statement.setObject(4, command.actorAccountId());
            statement.setObject(5, command.actorDeviceId());
            statement.setString(6, command.reaction().name());
            statement.setBoolean(7, command.active());
            statement.setString(8, command.clientOperationId());
            statement.setObject(9, OffsetDateTime.ofInstant(
                    allocation.occurredAt(), java.time.ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static MessageReactionResult.Applied applied(MessageReactionCommand command,
            boolean changed, Allocation allocation, boolean duplicate) {
        return new MessageReactionResult.Applied(
                command.conversationId(), command.messageId(), command.actorAccountId(),
                command.reaction(), command.active(), command.clientOperationId(), changed,
                allocation.sequence(), allocation.occurredAt(), duplicate);
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record Allocation(long sequence, Instant occurredAt) { }

    private record ExistingOperation(
            java.util.UUID conversationId,
            java.util.UUID actorDeviceId,
            java.util.UUID messageId,
            MessageReactionKind reaction,
            boolean active,
            boolean changed,
            long sequence,
            Instant occurredAt) { }
}
