package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.messaging.MessageEditCommand;
import com.fallingnight.chat.application.messaging.MessageEditPort;
import com.fallingnight.chat.application.messaging.MessageEditResult;
import com.fallingnight.chat.application.messaging.MessageMention;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Serialized V2-native text edits with exact operation replay and mixed ordering. */
public final class PostgresMessageEditAdapter implements MessageEditPort {
    private final DataSource dataSource;

    public PostgresMessageEditAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public MessageEditResult edit(MessageEditCommand command) {
        Objects.requireNonNull(command, "command");
        byte[] content = command.content();
        byte[] contentHash = sha256(content);
        byte[] mentionsHash = mentionsHash(command.mentions());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!authorizedActor(connection, command)) {
                    connection.rollback();
                    return MessageEditResult.Rejected.NOT_AUTHORIZED;
                }
                ExistingOperation existing = findExisting(connection, command);
                if (existing != null) {
                    connection.rollback();
                    return replay(existing, command, contentHash, mentionsHash);
                }
                LockedMessage target = lockEditableTarget(connection, command);
                if (target == null) {
                    connection.rollback();
                    return MessageEditResult.Rejected.NOT_AUTHORIZED;
                }
                if (!authorizedMentionTargets(connection, command)) {
                    connection.rollback();
                    return MessageEditResult.Rejected.NOT_AUTHORIZED;
                }
                Instant now = transactionTime(connection);
                if (target.revision != command.expectedRevision()) {
                    return persistRejection(connection, command, contentHash, mentionsHash,
                            "STALE_REVISION", target.revision, now);
                }
                if (now.isAfter(target.acceptedAt.plusSeconds(15 * 60))) {
                    return persistRejection(connection, command, contentHash, mentionsHash,
                            "WINDOW_EXPIRED", target.revision, now);
                }
                if (target.revision >= MessageEditCommand.MAX_REVISION) {
                    return persistRejection(connection, command, contentHash, mentionsHash,
                            "REVISION_LIMIT", target.revision, now);
                }
                boolean changed = !MessageDigest.isEqual(target.payloadHash, contentHash)
                        || !Arrays.equals(target.payload, content)
                        || !target.mentions.equals(command.mentions());
                int resultRevision = target.revision + (changed ? 1 : 0);
                long sequence = changed ? allocateSequence(connection, command.conversationId()) : 0;
                if (changed) {
                    updateMessage(connection, command, content, contentHash, resultRevision, now);
                    insertEntryAndEvent(connection, command, content, contentHash,
                            resultRevision, sequence, now);
                }
                if (!insertOperation(connection, command, contentHash, mentionsHash, "APPLIED",
                        resultRevision, changed, sequence, now)) {
                    connection.rollback();
                    return existingAfterRace(command, contentHash, mentionsHash);
                }
                connection.commit();
                return applied(command, resultRevision, content, changed, sequence, now, false);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new MessagePersistenceException("message edit failed", exception);
        }
    }

    private MessageEditResult persistRejection(Connection connection, MessageEditCommand command,
            byte[] contentHash, byte[] mentionsHash, String outcome,
            int resultRevision, Instant now)
            throws SQLException {
        if (!insertOperation(connection, command, contentHash, mentionsHash, outcome,
                resultRevision, false, 0, now)) {
            connection.rollback();
            return existingAfterRace(command, contentHash, mentionsHash);
        }
        connection.commit();
        return rejected(outcome);
    }

    private static boolean authorizedActor(Connection c, MessageEditCommand command)
            throws SQLException {
        String sql = "SELECT 1 FROM chat.conversation c "
                + "JOIN chat.conversation_member cm ON cm.conversation_id=c.id "
                + "JOIN chat.device d ON d.account_id=cm.account_id "
                + "JOIN chat.account a ON a.id=cm.account_id "
                + "LEFT JOIN chat.group_lifecycle gl ON gl.conversation_id=c.id "
                + "WHERE c.id=? AND cm.account_id=? AND cm.left_at IS NULL "
                + "AND d.id=? AND d.revoked_at IS NULL AND a.disabled_at IS NULL "
                + "AND (c.kind='DIRECT' OR (gl.conversation_id IS NOT NULL "
                + "AND gl.closed_at IS NULL))";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, command.conversationId());
            s.setObject(2, command.actorAccountId());
            s.setObject(3, command.actorDeviceId());
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }

    private static LockedMessage lockEditableTarget(Connection c, MessageEditCommand command)
            throws SQLException {
        String sql = "SELECT m.payload,m.payload_sha256,m.accepted_at,m.content_revision "
                + "FROM chat.conversation c JOIN chat.message m "
                + "ON m.conversation_id=c.id AND m.id=? WHERE c.id=? "
                + "AND m.sender_account_id=? AND m.message_type=1 AND m.deleted_at IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM chat.legacy_v1_message_map legacy "
                + "WHERE legacy.message_id=m.id) "
                + "AND NOT EXISTS (SELECT 1 FROM chat.message_recall_event recall "
                + "WHERE recall.conversation_id=c.id AND recall.message_id=m.id) "
                + "AND NOT EXISTS (SELECT 1 FROM chat.messages_deleted_event deleted "
                + "WHERE deleted.conversation_id=c.id AND deleted.source='V2' "
                + "AND jsonb_exists(deleted.message_ids,m.id::text)) FOR UPDATE OF c,m";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, command.messageId());
            s.setObject(2, command.conversationId());
            s.setObject(3, command.actorAccountId());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return null;
                return new LockedMessage(r.getBytes(1), r.getBytes(2),
                        r.getObject(3, OffsetDateTime.class).toInstant(), r.getInt(4),
                        readCurrentMentions(c, command.conversationId(), command.messageId()));
            }
        }
    }

    private static ExistingOperation findExisting(Connection c, MessageEditCommand command)
            throws SQLException {
        String sql = "SELECT conversation_id,actor_device_id,message_id,expected_revision,"
                + "content_type,requested_content_sha256,requested_mentions_sha256,"
                + "outcome,result_revision,changed,"
                + "conversation_sequence,occurred_at FROM chat.message_edit_operation "
                + "WHERE actor_account_id=? AND client_operation_id=?";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, command.actorAccountId());
            s.setString(2, command.clientOperationId());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return null;
                long sequence = r.getLong(11);
                boolean sequenceNull = r.wasNull();
                return new ExistingOperation(r.getObject(1, UUID.class),
                        r.getObject(2, UUID.class), r.getObject(3, UUID.class), r.getInt(4),
                        r.getInt(5), r.getBytes(6), r.getBytes(7), r.getString(8), r.getInt(9),
                        r.getBoolean(10), sequenceNull ? 0 : sequence,
                        r.getObject(12, OffsetDateTime.class).toInstant());
            }
        }
    }

    private MessageEditResult existingAfterRace(
            MessageEditCommand command, byte[] contentHash, byte[] mentionsHash)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ExistingOperation existing = findExisting(connection, command);
            if (existing == null) throw new SQLException("edit conflict row disappeared");
            return replay(existing, command, contentHash, mentionsHash);
        }
    }

    private static MessageEditResult replay(ExistingOperation existing,
            MessageEditCommand command, byte[] contentHash, byte[] mentionsHash) {
        boolean exact = existing.conversationId.equals(command.conversationId())
                && existing.deviceId.equals(command.actorDeviceId())
                && existing.messageId.equals(command.messageId())
                && existing.expectedRevision == command.expectedRevision()
                && existing.contentType == command.contentType()
                && MessageDigest.isEqual(existing.contentHash, contentHash)
                && MessageDigest.isEqual(existing.mentionsHash, mentionsHash);
        if (!exact) return MessageEditResult.Rejected.IDEMPOTENCY_CONFLICT;
        if (!existing.outcome.equals("APPLIED")) return rejected(existing.outcome);
        return applied(command, existing.resultRevision, command.content(), existing.changed,
                existing.sequence, existing.occurredAt, true);
    }

    private static long allocateSequence(Connection c, UUID conversationId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE chat.conversation "
                + "SET next_sequence=next_sequence+1,updated_at=transaction_timestamp() "
                + "WHERE id=? RETURNING next_sequence-1")) {
            s.setObject(1, conversationId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new SQLException("edit conversation disappeared");
                return r.getLong(1);
            }
        }
    }

    private static Instant transactionTime(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT transaction_timestamp()");
                ResultSet r = s.executeQuery()) {
            r.next();
            return r.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    private static void updateMessage(Connection c, MessageEditCommand command, byte[] content,
            byte[] contentHash, int revision, Instant now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE chat.message SET payload=?,"
                + "payload_sha256=?,content_revision=?,edited_at=? "
                + "WHERE conversation_id=? AND id=? AND content_revision=?")) {
            s.setBytes(1, content);
            s.setBytes(2, contentHash);
            s.setInt(3, revision);
            s.setObject(4, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            s.setObject(5, command.conversationId());
            s.setObject(6, command.messageId());
            s.setInt(7, command.expectedRevision());
            if (s.executeUpdate() != 1) throw new SQLException("locked edit target changed");
        }
        replaceCurrentMentions(c, command);
    }

    private static void insertEntryAndEvent(Connection c, MessageEditCommand command,
            byte[] content, byte[] contentHash, int revision, long sequence, Instant now)
            throws SQLException {
        OffsetDateTime time = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        try (PreparedStatement s = c.prepareStatement("INSERT INTO chat.conversation_entry "
                + "VALUES (?,?,'MESSAGE_EDITED',?)")) {
            s.setObject(1, command.conversationId());
            s.setLong(2, sequence);
            s.setObject(3, time);
            s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO chat.message_edit_event("
                + "conversation_id,conversation_sequence,message_id,content_revision,"
                + "content_type,content,content_sha256,actor_account_id,actor_device_id,"
                + "client_operation_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setObject(1, command.conversationId());
            s.setLong(2, sequence);
            s.setObject(3, command.messageId());
            s.setInt(4, revision);
            s.setInt(5, command.contentType());
            s.setBytes(6, content);
            s.setBytes(7, contentHash);
            s.setObject(8, command.actorAccountId());
            s.setObject(9, command.actorDeviceId());
            s.setString(10, command.clientOperationId());
            s.setObject(11, time);
            s.executeUpdate();
        }
        insertEventMentions(c, command, sequence);
    }

    private static boolean insertOperation(Connection c, MessageEditCommand command,
            byte[] contentHash, byte[] mentionsHash, String outcome,
            int resultRevision, boolean changed,
            long sequence, Instant now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO chat.message_edit_operation("
                + "actor_account_id,client_operation_id,conversation_id,actor_device_id,"
                + "message_id,expected_revision,content_type,requested_content_sha256,"
                + "requested_mentions_sha256,outcome,"
                + "result_revision,changed,conversation_sequence,occurred_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
            s.setObject(1, command.actorAccountId());
            s.setString(2, command.clientOperationId());
            s.setObject(3, command.conversationId());
            s.setObject(4, command.actorDeviceId());
            s.setObject(5, command.messageId());
            s.setInt(6, command.expectedRevision());
            s.setInt(7, command.contentType());
            s.setBytes(8, contentHash);
            s.setBytes(9, mentionsHash);
            s.setString(10, outcome);
            s.setInt(11, resultRevision);
            s.setBoolean(12, changed);
            if (changed) s.setLong(13, sequence); else s.setNull(13, Types.BIGINT);
            s.setObject(14, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            return s.executeUpdate() == 1;
        }
    }

    private static MessageEditResult.Applied applied(MessageEditCommand command, int revision,
            byte[] content, boolean changed, long sequence, Instant at, boolean duplicate) {
        return new MessageEditResult.Applied(command.conversationId(), command.messageId(),
                command.actorAccountId(), revision, command.contentType(), content,
                command.clientOperationId(), changed, sequence, at, duplicate,
                command.mentions());
    }

    private static MessageEditResult.Rejected rejected(String outcome) {
        return switch (outcome) {
            case "STALE_REVISION" -> MessageEditResult.Rejected.STALE_REVISION;
            case "WINDOW_EXPIRED" -> MessageEditResult.Rejected.WINDOW_EXPIRED;
            case "REVISION_LIMIT" -> MessageEditResult.Rejected.REVISION_LIMIT;
            default -> throw new IllegalArgumentException("unknown edit outcome: " + outcome);
        };
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static byte[] mentionsHash(List<MessageMention> mentions) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (MessageMention mention : mentions) {
                digest.update(mention.targetAccountId().toString()
                        .getBytes(StandardCharsets.US_ASCII));
                digest.update(ByteBuffer.allocate(8)
                        .putInt(mention.startUtf8Byte())
                        .putInt(mention.lengthUtf8Bytes()).array());
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static boolean authorizedMentionTargets(
            Connection c, MessageEditCommand command) throws SQLException {
        if (command.mentions().isEmpty()) return true;
        List<UUID> targets = command.mentions().stream()
                .map(MessageMention::targetAccountId).distinct().toList();
        java.sql.Array targetArray = c.createArrayOf("uuid", targets.toArray());
        String sql = "SELECT cm.account_id FROM chat.conversation_member cm "
                + "JOIN chat.account a ON a.id=cm.account_id "
                + "WHERE cm.conversation_id=? AND cm.account_id=ANY (?) "
                + "AND cm.left_at IS NULL AND a.disabled_at IS NULL FOR KEY SHARE OF cm,a";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, command.conversationId());
            s.setArray(2, targetArray);
            int found = 0;
            try (ResultSet r = s.executeQuery()) { while (r.next()) found++; }
            return found == targets.size();
        } finally {
            targetArray.free();
        }
    }

    private static List<MessageMention> readCurrentMentions(
            Connection c, UUID conversationId, UUID messageId) throws SQLException {
        List<MessageMention> mentions = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(
                "SELECT target_account_id,start_utf8_byte,length_utf8_bytes "
                + "FROM chat.message_mention WHERE conversation_id=? AND message_id=? "
                + "ORDER BY mention_ordinal")) {
            s.setObject(1, conversationId);
            s.setObject(2, messageId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) mentions.add(new MessageMention(
                        r.getObject(1, UUID.class), r.getInt(2), r.getInt(3)));
            }
        }
        return List.copyOf(mentions);
    }

    private static void replaceCurrentMentions(
            Connection c, MessageEditCommand command) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "DELETE FROM chat.message_mention WHERE conversation_id=? AND message_id=?")) {
            s.setObject(1, command.conversationId());
            s.setObject(2, command.messageId());
            s.executeUpdate();
        }
        insertMentions(c, "message_mention", command.conversationId(),
                command.messageId(), 0, command.mentions());
    }

    private static void insertEventMentions(
            Connection c, MessageEditCommand command, long sequence) throws SQLException {
        insertMentions(c, "message_edit_event_mention", command.conversationId(),
                null, sequence, command.mentions());
    }

    private static void insertMentions(
            Connection c, String table, UUID conversationId, UUID messageId,
            long sequence, List<MessageMention> mentions) throws SQLException {
        if (mentions.isEmpty()) return;
        String identity = table.equals("message_mention") ? "message_id" : "conversation_sequence";
        String sql = "INSERT INTO chat." + table + "(conversation_id," + identity
                + ",mention_ordinal,target_account_id,start_utf8_byte,length_utf8_bytes) "
                + "VALUES (?,?,?,?,?,?)";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int index = 0; index < mentions.size(); index++) {
                MessageMention mention = mentions.get(index);
                s.setObject(1, conversationId);
                if (messageId != null) s.setObject(2, messageId); else s.setLong(2, sequence);
                s.setInt(3, index);
                s.setObject(4, mention.targetAccountId());
                s.setInt(5, mention.startUtf8Byte());
                s.setInt(6, mention.lengthUtf8Bytes());
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    private static void rollback(Connection c, Exception original) {
        try { c.rollback(); } catch (SQLException failure) { original.addSuppressed(failure); }
    }

    private record LockedMessage(byte[] payload, byte[] payloadHash, Instant acceptedAt,
            int revision, List<MessageMention> mentions) { }

    private record ExistingOperation(UUID conversationId, UUID deviceId, UUID messageId,
            int expectedRevision, int contentType, byte[] contentHash, byte[] mentionsHash,
            String outcome,
            int resultRevision, boolean changed, long sequence, Instant occurredAt) { }
}
