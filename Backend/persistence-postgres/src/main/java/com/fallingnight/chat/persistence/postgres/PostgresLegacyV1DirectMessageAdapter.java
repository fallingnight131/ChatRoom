package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectMessageCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectMessagePort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectMessageResult;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Atomic canonical plus V1-mapped direct text/emoji submission. */
public final class PostgresLegacyV1DirectMessageAdapter implements LegacyV1DirectMessagePort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1DirectMessageAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public LegacyV1DirectMessageResult submit(LegacyV1DirectMessageCommand command) {
        Objects.requireNonNull(command, "command");
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return attempt(command);
            } catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new MessagePersistenceException("V1 direct message submission failed", last);
    }

    private LegacyV1DirectMessageResult attempt(LegacyV1DirectMessageCommand command)
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
                    return LegacyV1DirectMessageResult.Rejected.FRIENDSHIP_ACCESS_DENIED;
                }
                Existing existing = findExisting(connection, command.senderAccountId(),
                        command.clientMessageId());
                if (existing != null) {
                    LegacyV1DirectMessageResult result = existingResult(
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
        } finally {
            Arrays.fill(hash, (byte) 0);
        }
    }

    private static Target resolveTarget(
            Connection connection, LegacyV1DirectMessageCommand command) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT target.id AS target_id, target.username_key,
                       direct.conversation_id, mapping.legacy_conversation_id
                FROM chat.account actor
                JOIN chat.legacy_v1_account_map actor_map ON actor_map.account_id = actor.id
                JOIN chat.account target ON target.username_key = ?
                 AND target.disabled_at IS NULL
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
                JOIN chat.device device ON device.id = ? AND device.account_id = actor.id
                 AND device.revoked_at IS NULL
                WHERE actor.id = ? AND actor.disabled_at IS NULL
                  AND mapping.legacy_conversation_id BETWEEN 1 AND 2147483647
                FOR UPDATE OF conversation
                """)) {
            statement.setString(1, command.targetUsername());
            statement.setObject(2, command.senderDeviceId());
            statement.setObject(3, command.senderAccountId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Target result = new Target(
                        row.getObject("target_id", UUID.class),
                        row.getString("username_key"),
                        row.getObject("conversation_id", UUID.class),
                        row.getLong("legacy_conversation_id"));
                if (row.next()) throw new SQLException("V1 direct target returned duplicates");
                return result;
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
            statement.setObject(1, sender);
            statement.setString(2, clientId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Existing result = new Existing(
                        row.getObject("id", UUID.class),
                        row.getObject("conversation_id", UUID.class),
                        row.getLong("conversation_sequence"),
                        row.getObject("sender_device_id", UUID.class),
                        row.getInt("message_type"), row.getBytes("payload"),
                        row.getBytes("payload_sha256"),
                        row.getObject("accepted_at", OffsetDateTime.class).toInstant());
                if (row.next()) throw new SQLException("idempotency row returned duplicates");
                return result;
            }
        }
    }

    private static LegacyV1DirectMessageResult existingResult(
            Connection connection, LegacyV1DirectMessageCommand command, Target target,
            Existing existing, byte[] payload, byte[] hash) throws SQLException {
        if (!existing.conversationId().equals(target.conversationId())
                || !existing.senderDeviceId().equals(command.senderDeviceId())
                || existing.messageType() != 1
                || !Arrays.equals(existing.payload(), payload)
                || !MessageDigest.isEqual(existing.hash(), hash)) {
            return LegacyV1DirectMessageResult.Rejected.CLIENT_MESSAGE_ID_CONFLICT;
        }
        long legacyMessageId = mappedMessageId(
                connection, target, existing.messageId(), command.contentType());
        return accepted(true, target, legacyMessageId,
                existing.sequence(), existing.acceptedAt());
    }

    private static long mappedMessageId(
            Connection connection, Target target, UUID messageId, String legacyContentType)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT legacy_message_id, legacy_content_type
                FROM chat.legacy_v1_message_map
                WHERE legacy_kind = 'FRIENDSHIP' AND message_id = ?
                  AND legacy_conversation_id = ? AND conversation_id = ?
                """)) {
            statement.setObject(1, messageId);
            statement.setLong(2, target.legacyFriendshipId());
            statement.setObject(3, target.conversationId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || row.getLong(1) <= 0 || row.getLong(1) > Integer.MAX_VALUE
                        || !legacyContentType.equals(row.getString(2))) {
                    throw new SQLException("canonical V1 message has no valid mapping");
                }
                long result = row.getLong(1);
                if (row.next()) throw new SQLException("V1 message mapping returned duplicates");
                return result;
            }
        }
    }

    private static long allocateSequence(Connection connection, UUID conversationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation SET next_sequence = next_sequence + 1,
                    updated_at = transaction_timestamp()
                WHERE id = ? RETURNING next_sequence - 1
                """)) {
            statement.setObject(1, conversationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("direct conversation disappeared");
                return row.getLong(1);
            }
        }
    }

    private static Instant insertMessage(
            Connection connection, LegacyV1DirectMessageCommand command, Target target,
            UUID messageId, long sequence, byte[] payload, byte[] hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.conversation_entry(
                    conversation_id, conversation_sequence, entry_kind, occurred_at)
                VALUES (?, ?, 'MESSAGE', transaction_timestamp())
                """)) {
            statement.setObject(1, target.conversationId());
            statement.setLong(2, sequence);
            if (statement.executeUpdate() != 1) throw new SQLException("entry insert failed");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.message(id, conversation_id, conversation_sequence,
                    sender_account_id, sender_device_id, client_message_id,
                    message_type, payload, payload_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING accepted_at
                """)) {
            statement.setObject(1, messageId);
            statement.setObject(2, target.conversationId());
            statement.setLong(3, sequence);
            statement.setObject(4, command.senderAccountId());
            statement.setObject(5, command.senderDeviceId());
            statement.setString(6, command.clientMessageId());
            statement.setInt(7, 1);
            statement.setBytes(8, payload);
            statement.setBytes(9, hash);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("message insert returned no timestamp");
                return row.getObject(1, OffsetDateTime.class).toInstant();
            }
        }
    }

    private static long nextUnusedMessageId(Connection connection) throws SQLException {
        while (true) {
            long candidate;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT nextval('chat.legacy_v1_friend_message_id_seq')");
                    ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 message ID allocation returned no row");
                candidate = row.getLong(1);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT EXISTS (SELECT 1 FROM chat.legacy_v1_message_map
                        WHERE legacy_kind = 'FRIENDSHIP' AND legacy_message_id = ?)
                    """)) {
                statement.setLong(1, candidate);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new SQLException("V1 message occupancy returned no row");
                    if (!row.getBoolean(1)) return candidate;
                }
            }
        }
    }

    private static void insertMapping(
            Connection connection, Target target, UUID messageId, long legacyMessageId,
            String legacyContentType)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.legacy_v1_message_map(legacy_kind, legacy_message_id,
                    legacy_conversation_id, conversation_id, message_id, legacy_content_type)
                VALUES ('FRIENDSHIP', ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, legacyMessageId);
            statement.setLong(2, target.legacyFriendshipId());
            statement.setObject(3, target.conversationId());
            statement.setObject(4, messageId);
            statement.setString(5, legacyContentType);
            if (statement.executeUpdate() != 1) throw new SQLException("V1 mapping insert failed");
        }
    }

    private static LegacyV1DirectMessageResult.Accepted accepted(
            boolean duplicate, Target target, long legacyMessageId,
            long sequence, Instant acceptedAt) {
        return new LegacyV1DirectMessageResult.Accepted(
                duplicate, target.legacyFriendshipId(), legacyMessageId, sequence,
                acceptedAt, target.targetAccountId(), target.targetUsername());
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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

    private record Target(UUID targetAccountId, String targetUsername,
            UUID conversationId, long legacyFriendshipId) { }
    private record Existing(UUID messageId, UUID conversationId, long sequence,
            UUID senderDeviceId, int messageType, byte[] payload, byte[] hash,
            Instant acceptedAt) { }
}
