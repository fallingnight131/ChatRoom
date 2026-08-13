package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Serializable administrator-authorized V1 room admission-password state. */
public final class PostgresLegacyV1RoomPasswordAdapter implements LegacyV1RoomPasswordPort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1RoomPasswordAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RoomPasswordStatusResult status(
            UUID actorAccountId, long legacyRoomId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try {
                Target target = authorizedRoom(connection, actorAccountId, legacyRoomId, false);
                if (target == null) {
                    connection.commit();
                    return LegacyV1RoomPasswordStatusResult.Rejected.ROOM_ADMIN_REQUIRED;
                }
                connection.commit();
                return new LegacyV1RoomPasswordStatusResult.Authorized(target.conversationId(),
                        target.legacyRoomId(), target.hasPassword(), target.updatedAt());
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException("V1 room password status failed", exception);
        }
    }

    @Override public LegacyV1RoomPasswordUpdateResult update(
            LegacyV1RoomPasswordIntent intent) {
        Objects.requireNonNull(intent, "intent"); SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return updateAttempt(intent); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException("V1 room password update failed", last);
    }

    private LegacyV1RoomPasswordUpdateResult updateAttempt(
            LegacyV1RoomPasswordIntent intent) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Target target = authorizedRoom(connection, intent.actorAccountId(),
                        intent.legacyRoomId(), true);
                if (target == null) {
                    connection.commit();
                    return LegacyV1RoomPasswordUpdateResult.Rejected.ROOM_ADMIN_REQUIRED;
                }
                boolean requested = intent.encodedPassword().isPresent();
                if (!requested && !target.hasPassword()) {
                    connection.commit();
                    return updated(target, false, false, target.updatedAt());
                }
                if (requested && target.hasPassword() && target.passwordTag() != null
                        && constantTimeEquals(target.passwordTag(), intent.encodedPassword()
                                .orElseThrow().idempotencyTag())) {
                    connection.commit();
                    return updated(target, true, false, target.updatedAt());
                }
                Instant changedAt;
                if (requested) changedAt = upsertCredential(connection, target.conversationId(),
                        intent.encodedPassword().orElseThrow());
                else changedAt = deleteCredential(connection, target.conversationId());
                touchConversation(connection, target.conversationId());
                connection.commit();
                return updated(target, requested, true, changedAt);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        }
    }

    private static Target authorizedRoom(Connection connection, UUID actor, long roomId,
            boolean lock) throws SQLException {
        String suffix = lock ? " FOR UPDATE OF conversation" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation.id, room.legacy_conversation_id,
                       credential.encoded_password IS NOT NULL AS has_password,
                       credential.password_idempotency_tag,
                       COALESCE(credential.updated_at, conversation.updated_at) AS updated_at
                FROM chat.account actor
                JOIN chat.legacy_v1_account_map actor_map ON actor_map.account_id = actor.id
                JOIN chat.legacy_v1_conversation_map room
                  ON room.legacy_kind = 'ROOM' AND room.legacy_conversation_id = ?
                JOIN chat.conversation conversation
                  ON conversation.id = room.conversation_id AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = conversation.id AND lifecycle.closed_at IS NULL
                JOIN chat.conversation_member member
                  ON member.conversation_id = conversation.id
                 AND member.account_id = actor.id AND member.left_at IS NULL
                 AND member.role IN ('OWNER', 'ADMIN')
                LEFT JOIN chat.group_join_credential credential
                  ON credential.conversation_id = conversation.id
                WHERE actor.id = ? AND actor.disabled_at IS NULL
                """ + suffix)) {
            statement.setLong(1, roomId); statement.setObject(2, actor);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Target result = new Target(row.getObject("id", UUID.class),
                        row.getLong("legacy_conversation_id"), row.getBoolean("has_password"),
                        row.getString("password_idempotency_tag"),
                        row.getObject("updated_at", OffsetDateTime.class).toInstant());
                if (row.next()) throw new SQLException("V1 room password target duplicated");
                return result;
            }
        }
    }

    private static Instant upsertCredential(Connection connection, UUID conversationId,
            LegacyV1RoomPasswordEncoding encoding) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.group_join_credential(
                    conversation_id, encoded_password, password_idempotency_tag, updated_at)
                VALUES (?, ?, ?, transaction_timestamp())
                ON CONFLICT (conversation_id) DO UPDATE
                SET encoded_password = EXCLUDED.encoded_password,
                    password_idempotency_tag = EXCLUDED.password_idempotency_tag,
                    updated_at = transaction_timestamp()
                RETURNING updated_at
                """)) {
            statement.setObject(1, conversationId);
            statement.setString(2, encoding.encodedHash());
            statement.setString(3, encoding.idempotencyTag());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 room password was not stored");
                return row.getObject(1, OffsetDateTime.class).toInstant();
            }
        }
    }

    private static Instant deleteCredential(Connection connection, UUID conversationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM chat.group_join_credential WHERE conversation_id = ?
                RETURNING transaction_timestamp()
                """)) {
            statement.setObject(1, conversationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 room password disappeared while locked");
                return row.getObject(1, OffsetDateTime.class).toInstant();
            }
        }
    }

    private static void touchConversation(Connection connection, UUID conversationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chat.conversation SET updated_at = transaction_timestamp() WHERE id = ?")) {
            statement.setObject(1, conversationId);
            if (statement.executeUpdate() != 1) throw new SQLException("V1 room disappeared");
        }
    }

    private static LegacyV1RoomPasswordUpdateResult.Updated updated(Target target,
            boolean hasPassword, boolean changed, Instant updatedAt) {
        return new LegacyV1RoomPasswordUpdateResult.Updated(target.conversationId(),
                target.legacyRoomId(), hasPassword, changed, updatedAt);
    }
    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
    private static boolean retryable(SQLException exception) {
        for (SQLException current = exception; current != null;
                current = current.getNextException())
            if ("40001".equals(current.getSQLState()) || "40P01".equals(current.getSQLState()))
                return true;
        return false;
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }
    private record Target(UUID conversationId, long legacyRoomId, boolean hasPassword,
            String passwordTag, Instant updatedAt) { }
}
