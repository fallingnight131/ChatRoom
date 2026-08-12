package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMessageCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMessagePort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMessageResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Atomic canonical plus V1-mapped room text/emoji submission. */
public final class PostgresLegacyV1RoomMessageAdapter implements LegacyV1RoomMessagePort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1RoomMessageAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RoomMessageResult submit(LegacyV1RoomMessageCommand command) {
        Objects.requireNonNull(command, "command");
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(command); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new MessagePersistenceException("V1 room message submission failed", last);
    }

    private LegacyV1RoomMessageResult attempt(LegacyV1RoomMessageCommand command)
            throws SQLException {
        byte[] payload = command.content().getBytes(StandardCharsets.UTF_8);
        byte[] hash = sha256(payload);
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Target target = resolveTarget(connection, command);
                if (target == null) {
                    connection.commit();
                    return LegacyV1RoomMessageResult.Rejected.ROOM_ACCESS_DENIED;
                }
                Existing existing = findExisting(connection, command.senderAccountId(),
                        command.clientMessageId());
                if (existing != null) {
                    LegacyV1RoomMessageResult result = existingResult(
                            connection, command, target, existing, payload, hash);
                    connection.commit();
                    return result;
                }
                long sequence = allocateSequence(connection, target.conversationId());
                UUID messageId = UUID.randomUUID();
                Instant acceptedAt = insertMessage(
                        connection, command, target, messageId, sequence, payload, hash);
                long legacyMessageId = nextUnusedMessageId(connection);
                insertMapping(connection, target, messageId, legacyMessageId,
                        command.contentType());
                connection.commit();
                return accepted(false, target, legacyMessageId, sequence, acceptedAt);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } finally { Arrays.fill(hash, (byte) 0); }
    }

    private static Target resolveTarget(Connection connection,
            LegacyV1RoomMessageCommand command) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation.id AS conversation_id,
                       mapping.legacy_conversation_id
                FROM chat.account actor
                JOIN chat.legacy_v1_account_map actor_map ON actor_map.account_id = actor.id
                JOIN chat.device device ON device.id = ? AND device.account_id = actor.id
                 AND device.revoked_at IS NULL
                JOIN chat.legacy_v1_conversation_map mapping
                  ON mapping.legacy_kind = 'ROOM'
                 AND mapping.legacy_conversation_id = ?
                JOIN chat.conversation conversation
                  ON conversation.id = mapping.conversation_id
                 AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = conversation.id
                 AND lifecycle.closed_at IS NULL
                JOIN chat.conversation_member member
                  ON member.conversation_id = conversation.id
                 AND member.account_id = actor.id AND member.left_at IS NULL
                WHERE actor.id = ? AND actor.disabled_at IS NULL
                  AND mapping.legacy_conversation_id BETWEEN 1 AND 2147483647
                FOR UPDATE OF conversation
                """)) {
            statement.setObject(1, command.senderDeviceId());
            statement.setLong(2, command.legacyRoomId());
            statement.setObject(3, command.senderAccountId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Target target = new Target(row.getObject("conversation_id", UUID.class),
                        row.getLong("legacy_conversation_id"));
                if (row.next()) throw new SQLException("V1 room target returned duplicates");
                return target;
            }
        }
    }

    private static Existing findExisting(Connection connection, UUID sender, String clientId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, conversation_id, conversation_sequence, sender_device_id,
                       message_type, payload, payload_sha256, accepted_at
                FROM chat.message
                WHERE sender_account_id = ? AND client_message_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, sender); statement.setString(2, clientId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Existing existing = new Existing(row.getObject("id", UUID.class),
                        row.getObject("conversation_id", UUID.class),
                        row.getLong("conversation_sequence"),
                        row.getObject("sender_device_id", UUID.class),
                        row.getInt("message_type"), row.getBytes("payload"),
                        row.getBytes("payload_sha256"),
                        row.getObject("accepted_at", OffsetDateTime.class).toInstant());
                if (row.next()) throw new SQLException("room idempotency row duplicated");
                return existing;
            }
        }
    }

    private static LegacyV1RoomMessageResult existingResult(Connection connection,
            LegacyV1RoomMessageCommand command, Target target, Existing existing,
            byte[] payload, byte[] hash) throws SQLException {
        if (!existing.conversationId().equals(target.conversationId())
                || !existing.senderDeviceId().equals(command.senderDeviceId())
                || existing.messageType() != 1 || !Arrays.equals(existing.payload(), payload)
                || !MessageDigest.isEqual(existing.hash(), hash)) {
            return LegacyV1RoomMessageResult.Rejected.CLIENT_MESSAGE_ID_CONFLICT;
        }
        long legacyMessageId = mappedMessageId(connection, target,
                existing.messageId(), command.contentType());
        return accepted(true, target, legacyMessageId,
                existing.sequence(), existing.acceptedAt());
    }

    private static long mappedMessageId(Connection connection, Target target,
            UUID messageId, String contentType) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT legacy_message_id, legacy_content_type
                FROM chat.legacy_v1_message_map
                WHERE legacy_kind = 'ROOM' AND message_id = ?
                  AND legacy_conversation_id = ? AND conversation_id = ?
                """)) {
            statement.setObject(1, messageId);
            statement.setLong(2, target.legacyRoomId());
            statement.setObject(3, target.conversationId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || row.getLong(1) <= 0 || row.getLong(1) > Integer.MAX_VALUE
                        || !contentType.equals(row.getString(2)))
                    throw new SQLException("canonical V1 room message has no valid mapping");
                long result = row.getLong(1);
                if (row.next()) throw new SQLException("V1 room message mapping duplicated");
                return result;
            }
        }
    }

    private static long allocateSequence(Connection connection, UUID conversationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation SET next_sequence = next_sequence + 1,
                    updated_at = transaction_timestamp()
                WHERE id = ? AND kind = 'GROUP' RETURNING next_sequence - 1
                """)) {
            statement.setObject(1, conversationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 room disappeared");
                return row.getLong(1);
            }
        }
    }

    private static Instant insertMessage(Connection connection,
            LegacyV1RoomMessageCommand command, Target target, UUID messageId,
            long sequence, byte[] payload, byte[] hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.conversation_entry(
                    conversation_id, conversation_sequence, entry_kind, occurred_at)
                VALUES (?, ?, 'MESSAGE', transaction_timestamp())
                """)) {
            statement.setObject(1, target.conversationId()); statement.setLong(2, sequence);
            if (statement.executeUpdate() != 1) throw new SQLException("room entry insert failed");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.message(id, conversation_id, conversation_sequence,
                    sender_account_id, sender_device_id, client_message_id,
                    message_type, payload, payload_sha256)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?) RETURNING accepted_at
                """)) {
            statement.setObject(1, messageId); statement.setObject(2, target.conversationId());
            statement.setLong(3, sequence); statement.setObject(4, command.senderAccountId());
            statement.setObject(5, command.senderDeviceId());
            statement.setString(6, command.clientMessageId()); statement.setBytes(7, payload);
            statement.setBytes(8, hash);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("room message timestamp missing");
                return row.getObject(1, OffsetDateTime.class).toInstant();
            }
        }
    }

    private static long nextUnusedMessageId(Connection connection) throws SQLException {
        while (true) {
            long candidate;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT nextval('chat.legacy_v1_room_message_id_seq')");
                    ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 room message ID missing");
                candidate = row.getLong(1);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT EXISTS (SELECT 1 FROM chat.legacy_v1_message_map
                        WHERE legacy_kind = 'ROOM' AND legacy_message_id = ?)
                    """)) {
                statement.setLong(1, candidate);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new SQLException("V1 room ID occupancy missing");
                    if (!row.getBoolean(1)) return candidate;
                }
            }
        }
    }

    private static void insertMapping(Connection connection, Target target, UUID messageId,
            long legacyMessageId, String contentType) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.legacy_v1_message_map(legacy_kind, legacy_message_id,
                    legacy_conversation_id, conversation_id, message_id, legacy_content_type)
                VALUES ('ROOM', ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, legacyMessageId); statement.setLong(2, target.legacyRoomId());
            statement.setObject(3, target.conversationId()); statement.setObject(4, messageId);
            statement.setString(5, contentType);
            if (statement.executeUpdate() != 1) throw new SQLException("V1 room mapping failed");
        }
    }

    private static LegacyV1RoomMessageResult.Accepted accepted(boolean duplicate, Target target,
            long legacyMessageId, long sequence, Instant acceptedAt) {
        return new LegacyV1RoomMessageResult.Accepted(duplicate, target.legacyRoomId(),
                legacyMessageId, sequence, acceptedAt, target.conversationId());
    }
    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static boolean retryable(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException())
            if ("40001".equals(current.getSQLState()) || "23505".equals(current.getSQLState()))
                return true;
        return false;
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); } catch (SQLException failure) { original.addSuppressed(failure); }
    }
    private record Target(UUID conversationId, long legacyRoomId) { }
    private record Existing(UUID messageId, UUID conversationId, long sequence,
            UUID senderDeviceId, int messageType, byte[] payload, byte[] hash,
            Instant acceptedAt) { }
}
