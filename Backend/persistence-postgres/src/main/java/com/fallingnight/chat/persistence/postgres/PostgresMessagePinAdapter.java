package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.messaging.MessagePinCommand;
import com.fallingnight.chat.application.messaging.MessagePinPort;
import com.fallingnight.chat.application.messaging.MessagePinResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Atomic shared pin state with exact operation replay and ordered change events. */
public final class PostgresMessagePinAdapter implements MessagePinPort {
    private static final int MAX_ACTIVE_PINS = 50;
    private final DataSource dataSource;

    public PostgresMessagePinAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public MessagePinResult set(MessagePinCommand command) {
        Objects.requireNonNull(command, "command");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!authorizedActor(connection, command)) {
                    connection.rollback();
                    return MessagePinResult.Rejected.NOT_AUTHORIZED;
                }
                Existing existing = findExisting(connection, command);
                if (existing != null) {
                    connection.rollback();
                    return replay(existing, command);
                }
                if (!lockTarget(connection, command)) {
                    connection.rollback();
                    return MessagePinResult.Rejected.NOT_AUTHORIZED;
                }
                boolean current = currentlyPinned(connection, command);
                boolean changed = current != command.pinned();
                Instant now = transactionTime(connection);
                if (changed && command.pinned() && activeCount(connection, command) >= MAX_ACTIVE_PINS) {
                    if (!insertOperation(connection, command, "LIMIT_REACHED", false, 0, now)) {
                        connection.rollback();
                        return existingAfterRace(command);
                    }
                    connection.commit();
                    return MessagePinResult.Rejected.LIMIT_REACHED;
                }
                long sequence = changed ? allocateSequence(connection, command.conversationId()) : 0;
                if (changed) {
                    updateState(connection, command, sequence, now);
                    insertEntryAndEvent(connection, command, sequence, now);
                }
                if (!insertOperation(connection, command, "APPLIED", changed, sequence, now)) {
                    connection.rollback();
                    return existingAfterRace(command);
                }
                connection.commit();
                return applied(command, changed, sequence, now, false);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new MessagePersistenceException("message pin failed", exception);
        }
    }

    private static boolean authorizedActor(Connection c, MessagePinCommand command)
            throws SQLException {
        String sql = "SELECT 1 FROM chat.conversation c "
                + "JOIN chat.conversation_member cm ON cm.conversation_id=c.id "
                + "JOIN chat.device d ON d.account_id=cm.account_id "
                + "JOIN chat.account a ON a.id=cm.account_id "
                + "LEFT JOIN chat.group_lifecycle gl ON gl.conversation_id=c.id "
                + "WHERE c.id=? AND cm.account_id=? AND cm.left_at IS NULL "
                + "AND d.id=? AND d.revoked_at IS NULL AND a.disabled_at IS NULL "
                + "AND (c.kind='DIRECT' OR (gl.conversation_id IS NOT NULL "
                + "AND gl.closed_at IS NULL AND cm.role IN ('OWNER','ADMIN')))";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, command.conversationId()); s.setObject(2, command.actorAccountId());
            s.setObject(3, command.actorDeviceId());
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }

    private static boolean lockTarget(Connection c, MessagePinCommand command) throws SQLException {
        String sql = "SELECT 1 FROM chat.conversation c JOIN chat.message m "
                + "ON m.conversation_id=c.id AND m.id=? WHERE c.id=? "
                + "AND NOT EXISTS (SELECT 1 FROM chat.message_recall_event r "
                + "WHERE r.conversation_id=c.id AND r.message_id=m.id) FOR UPDATE OF c,m";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, command.messageId()); s.setObject(2, command.conversationId());
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }

    private static Existing findExisting(Connection c, MessagePinCommand command) throws SQLException {
        String sql = "SELECT conversation_id,actor_device_id,message_id,desired_pinned,outcome,"
                + "changed,conversation_sequence,occurred_at FROM chat.message_pin_operation "
                + "WHERE actor_account_id=? AND client_operation_id=?";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, command.actorAccountId()); s.setString(2, command.clientOperationId());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return null;
                long sequence = r.getLong(7);
                return new Existing(r.getObject(1, UUID.class), r.getObject(2, UUID.class),
                        r.getObject(3, UUID.class), r.getBoolean(4), r.getString(5),
                        r.getBoolean(6), r.wasNull() ? 0 : sequence,
                        r.getObject(8, OffsetDateTime.class).toInstant());
            }
        }
    }

    private MessagePinResult existingAfterRace(MessagePinCommand command) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            Existing existing = findExisting(c, command);
            if (existing == null) throw new SQLException("pin conflict row disappeared");
            return replay(existing, command);
        }
    }

    private static MessagePinResult replay(Existing e, MessagePinCommand command) {
        boolean exact = e.conversationId.equals(command.conversationId())
                && e.deviceId.equals(command.actorDeviceId())
                && e.messageId.equals(command.messageId()) && e.pinned == command.pinned();
        if (!exact) return MessagePinResult.Rejected.IDEMPOTENCY_CONFLICT;
        if (e.outcome.equals("LIMIT_REACHED")) return MessagePinResult.Rejected.LIMIT_REACHED;
        return applied(command, e.changed, e.sequence, e.occurredAt, true);
    }

    private static boolean currentlyPinned(Connection c, MessagePinCommand command) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT 1 FROM chat.message_pin WHERE conversation_id=? AND message_id=?")) {
            s.setObject(1, command.conversationId()); s.setObject(2, command.messageId());
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }

    private static int activeCount(Connection c, MessagePinCommand command) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT count(*) FROM chat.message_pin WHERE conversation_id=?")) {
            s.setObject(1, command.conversationId());
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }

    private static long allocateSequence(Connection c, UUID conversation) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE chat.conversation "
                + "SET next_sequence=next_sequence+1,updated_at=transaction_timestamp() "
                + "WHERE id=? RETURNING next_sequence-1")) {
            s.setObject(1, conversation); try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new SQLException("pin conversation disappeared");
                return r.getLong(1);
            }
        }
    }

    private static Instant transactionTime(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT transaction_timestamp()");
                ResultSet r = s.executeQuery()) {
            r.next(); return r.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    private static void updateState(Connection c, MessagePinCommand command, long seq, Instant now)
            throws SQLException {
        if (command.pinned()) {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO chat.message_pin "
                    + "VALUES (?,?,?,?,?)")) {
                s.setObject(1, command.conversationId()); s.setObject(2, command.messageId());
                s.setObject(3, command.actorAccountId()); s.setLong(4, seq);
                s.setObject(5, OffsetDateTime.ofInstant(now, ZoneOffset.UTC)); s.executeUpdate();
            }
        } else try (PreparedStatement s = c.prepareStatement(
                "DELETE FROM chat.message_pin WHERE conversation_id=? AND message_id=?")) {
            s.setObject(1, command.conversationId()); s.setObject(2, command.messageId());
            if (s.executeUpdate() != 1) throw new SQLException("pin state changed while locked");
        }
    }

    private static void insertEntryAndEvent(Connection c, MessagePinCommand command,
            long seq, Instant now) throws SQLException {
        OffsetDateTime time = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        try (PreparedStatement s = c.prepareStatement("INSERT INTO chat.conversation_entry "
                + "VALUES (?,?,'MESSAGE_PIN_CHANGED',?)")) {
            s.setObject(1, command.conversationId()); s.setLong(2, seq); s.setObject(3, time);
            s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO chat.message_pin_event("
                + "conversation_id,conversation_sequence,message_id,actor_account_id,"
                + "actor_device_id,pinned,client_operation_id,occurred_at) VALUES (?,?,?,?,?,?,?,?)")) {
            s.setObject(1, command.conversationId()); s.setLong(2, seq);
            s.setObject(3, command.messageId()); s.setObject(4, command.actorAccountId());
            s.setObject(5, command.actorDeviceId()); s.setBoolean(6, command.pinned());
            s.setString(7, command.clientOperationId()); s.setObject(8, time); s.executeUpdate();
        }
    }

    private static boolean insertOperation(Connection c, MessagePinCommand command,
            String outcome, boolean changed, long sequence, Instant now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO chat.message_pin_operation("
                + "actor_account_id,client_operation_id,conversation_id,actor_device_id,message_id,"
                + "desired_pinned,outcome,changed,conversation_sequence,occurred_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
            s.setObject(1, command.actorAccountId()); s.setString(2, command.clientOperationId());
            s.setObject(3, command.conversationId()); s.setObject(4, command.actorDeviceId());
            s.setObject(5, command.messageId()); s.setBoolean(6, command.pinned());
            s.setString(7, outcome); s.setBoolean(8, changed);
            if (changed) s.setLong(9, sequence); else s.setNull(9, Types.BIGINT);
            s.setObject(10, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            return s.executeUpdate() == 1;
        }
    }

    private static MessagePinResult.Applied applied(MessagePinCommand c, boolean changed,
            long sequence, Instant at, boolean duplicate) {
        return new MessagePinResult.Applied(c.conversationId(), c.messageId(), c.actorAccountId(),
                c.pinned(), c.clientOperationId(), changed, sequence, at, duplicate);
    }

    private static void rollback(Connection c, Exception original) {
        try { c.rollback(); } catch (SQLException failure) { original.addSuppressed(failure); }
    }

    private record Existing(UUID conversationId, UUID deviceId, UUID messageId, boolean pinned,
            String outcome, boolean changed, long sequence, Instant occurredAt) { }
}
