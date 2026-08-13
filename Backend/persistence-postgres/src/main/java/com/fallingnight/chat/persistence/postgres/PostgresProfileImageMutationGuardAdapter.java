package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.profile.*;
import java.sql.*;
import java.util.Arrays;
import java.util.Objects;
import javax.sql.DataSource;

/** Preflight authorization and durable unreferenced-object cleanup intent. */
public final class PostgresProfileImageMutationGuardAdapter
        implements ProfileImageMutationAuthorizationPort, ProfileImageOrphanCleanupPort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresProfileImageMutationGuardAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public ProfileImageMutationAuthorization authorize(ProfileImageTarget target) {
        Objects.requireNonNull(target, "target");
        try (Connection connection = dataSource.getConnection()) {
            if (target instanceof ProfileImageTarget.Account account)
                return accountAuthorized(connection, account.actorAccountId())
                        ? ProfileImageMutationAuthorization.AUTHORIZED
                        : ProfileImageMutationAuthorization.ACCOUNT_UNAVAILABLE;
            ProfileImageTarget.LegacyRoom room = (ProfileImageTarget.LegacyRoom) target;
            return roomAdminAuthorized(connection, room)
                    ? ProfileImageMutationAuthorization.AUTHORIZED
                    : ProfileImageMutationAuthorization.ROOM_ADMIN_REQUIRED;
        } catch (SQLException exception) {
            throw new ConversationPersistenceException(
                    "profile image mutation authorization failed", exception);
        }
    }

    @Override public void requestIfUnreferenced(ProfileImageObjectEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence"); SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { requestOnce(evidence); return; }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException(
                "profile image orphan cleanup request failed", last);
    }

    private void requestOnce(ProfileImageObjectEvidence evidence) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                registerExact(connection, evidence);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE chat.profile_image_object object
                        SET cleanup_requested_at = COALESCE(
                                object.cleanup_requested_at, transaction_timestamp()),
                            delete_confirmed_at = NULL
                        WHERE object.object_key = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM chat.account_profile_image current
                              WHERE current.object_key = object.object_key)
                          AND NOT EXISTS (
                              SELECT 1 FROM chat.group_profile_image current
                              WHERE current.object_key = object.object_key)
                        """)) {
                    statement.setString(1, evidence.objectKey()); statement.executeUpdate();
                }
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                try { connection.rollback(); }
                catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        }
    }

    private static boolean accountAuthorized(Connection connection,
            java.util.UUID accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE account.id = ? AND account.disabled_at IS NULL
                """)) {
            statement.setObject(1, accountId);
            try (ResultSet row = statement.executeQuery()) {
                boolean found = row.next();
                if (found && row.next()) throw new SQLException("profile account mapping duplicated");
                return found;
            }
        }
    }

    private static boolean roomAdminAuthorized(Connection connection,
            ProfileImageTarget.LegacyRoom room) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM chat.legacy_v1_conversation_map mapping
                JOIN chat.conversation conversation ON conversation.id = mapping.conversation_id
                  AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle ON lifecycle.conversation_id = conversation.id
                  AND lifecycle.closed_at IS NULL
                JOIN chat.conversation_member actor ON actor.conversation_id = conversation.id
                  AND actor.account_id = ? AND actor.left_at IS NULL
                  AND actor.role IN ('OWNER', 'ADMIN')
                JOIN chat.account account ON account.id = actor.account_id
                  AND account.disabled_at IS NULL
                JOIN chat.legacy_v1_account_map actor_map ON actor_map.account_id = account.id
                WHERE mapping.legacy_kind = 'ROOM' AND mapping.legacy_conversation_id = ?
                """)) {
            statement.setObject(1, room.actorAccountId());
            statement.setLong(2, room.legacyRoomId());
            try (ResultSet row = statement.executeQuery()) {
                boolean found = row.next();
                if (found && row.next()) throw new SQLException("profile room mapping duplicated");
                return found;
            }
        }
    }

    private static void registerExact(Connection connection,
            ProfileImageObjectEvidence evidence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.profile_image_object
                    (object_key, byte_size, content_sha256, media_type)
                VALUES (?, ?, ?, ?) ON CONFLICT (object_key) DO NOTHING
                """)) {
            statement.setString(1, evidence.objectKey());
            statement.setLong(2, evidence.byteSize());
            statement.setBytes(3, evidence.contentSha256());
            statement.setString(4, evidence.mediaType()); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT byte_size, content_sha256, media_type
                FROM chat.profile_image_object WHERE object_key = ? FOR UPDATE
                """)) {
            statement.setString(1, evidence.objectKey());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()
                        || row.getLong("byte_size") != evidence.byteSize()
                        || !Arrays.equals(row.getBytes("content_sha256"),
                                evidence.contentSha256())
                        || !evidence.mediaType().equals(row.getString("media_type")))
                    throw new SQLException("profile image orphan evidence conflicts");
                if (row.next()) throw new SQLException("profile image object duplicated");
            }
        }
    }

    private static boolean retryable(SQLException exception) {
        return "40001".equals(exception.getSQLState()) || "40P01".equals(exception.getSQLState());
    }
}
