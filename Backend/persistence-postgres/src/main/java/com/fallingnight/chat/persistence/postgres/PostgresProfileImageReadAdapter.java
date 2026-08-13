package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.profile.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Authorized metadata projection for private profile-image object reads. */
public final class PostgresProfileImageReadAdapter implements ProfileImageReadPort {
    private final DataSource dataSource;

    public PostgresProfileImageReadAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public ProfileImageReadResult read(ProfileImageReadTarget target) {
        Objects.requireNonNull(target, "target");
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try {
                if (!eligibleActor(connection, target.actorAccountId())) {
                    connection.commit();
                    return ProfileImageReadResult.Rejected.ACCESS_DENIED;
                }
                ProfileImageReadResult result = target instanceof
                        ProfileImageReadTarget.AccountByUsername account
                        ? readAccount(connection, account.username())
                        : readRoom(connection, (ProfileImageReadTarget.LegacyRoom) target);
                connection.commit(); return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException("profile image read failed", exception);
        }
    }

    private static boolean eligibleActor(Connection connection, UUID actor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE account.id = ? AND account.disabled_at IS NULL
                """)) {
            statement.setObject(1, actor);
            try (ResultSet row = statement.executeQuery()) { return row.next() && !row.next(); }
        }
    }

    private static ProfileImageReadResult readAccount(Connection connection, String username)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account.id AS target_id, current.object_key, current.width,
                       current.height, current.version, current.updated_at,
                       object.byte_size, object.content_sha256, object.media_type,
                       object.cleanup_requested_at, object.delete_confirmed_at
                FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                LEFT JOIN chat.account_profile_image current ON current.account_id = account.id
                LEFT JOIN chat.profile_image_object object ON object.object_key = current.object_key
                WHERE account.username_key = ? AND account.disabled_at IS NULL
                """)) {
            statement.setString(1, username);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return ProfileImageReadResult.Missing.INSTANCE;
                ProfileImageReadResult result = project(row);
                if (row.next()) throw new SQLException("profile account read duplicated");
                return result;
            }
        }
    }

    private static ProfileImageReadResult readRoom(Connection connection,
            ProfileImageReadTarget.LegacyRoom target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation.id AS target_id, current.object_key, current.width,
                       current.height, current.version, current.updated_at,
                       object.byte_size, object.content_sha256, object.media_type,
                       object.cleanup_requested_at, object.delete_confirmed_at
                FROM chat.legacy_v1_conversation_map mapping
                JOIN chat.conversation conversation ON conversation.id = mapping.conversation_id
                  AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle ON lifecycle.conversation_id = conversation.id
                  AND lifecycle.closed_at IS NULL
                JOIN chat.conversation_member actor ON actor.conversation_id = conversation.id
                  AND actor.account_id = ? AND actor.left_at IS NULL
                LEFT JOIN chat.group_profile_image current
                  ON current.conversation_id = conversation.id
                LEFT JOIN chat.profile_image_object object ON object.object_key = current.object_key
                WHERE mapping.legacy_kind = 'ROOM' AND mapping.legacy_conversation_id = ?
                """)) {
            statement.setObject(1, target.actorAccountId());
            statement.setLong(2, target.legacyRoomId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return ProfileImageReadResult.Rejected.ACCESS_DENIED;
                ProfileImageReadResult result = project(row);
                if (row.next()) throw new SQLException("profile room read duplicated");
                return result;
            }
        }
    }

    private static ProfileImageReadResult project(ResultSet row) throws SQLException {
        String objectKey = row.getString("object_key");
        if (objectKey == null) return ProfileImageReadResult.Missing.INSTANCE;
        if (row.getObject("cleanup_requested_at") != null
                || row.getObject("delete_confirmed_at") != null)
            throw new SQLException("referenced profile image is pending cleanup");
        byte[] sha = row.getBytes("content_sha256");
        if (sha == null) throw new SQLException("profile image object evidence missing");
        return new ProfileImageReadResult.Found(new ProfileImageObjectEvidence(
                objectKey, row.getLong("byte_size"), sha, row.getString("media_type")),
                row.getInt("width"), row.getInt("height"), row.getLong("version"),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); } catch (SQLException rollback) { original.addSuppressed(rollback); }
    }
}
