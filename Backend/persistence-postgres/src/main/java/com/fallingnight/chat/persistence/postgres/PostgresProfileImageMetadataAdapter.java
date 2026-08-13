package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.profile.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import javax.sql.DataSource;

/** Serializable metadata-only avatar pointer commit; object bytes stay external. */
public final class PostgresProfileImageMetadataAdapter implements ProfileImageMetadataPort {
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_AUDIENCE_MEMBERS = 100_000;
    private final DataSource dataSource;

    public PostgresProfileImageMetadataAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public ProfileImageMetadataResult commit(ProfileImageMetadataCommand command) {
        Objects.requireNonNull(command, "command"); SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(command); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException("profile image metadata commit failed", last);
    }

    private ProfileImageMetadataResult attempt(ProfileImageMetadataCommand command)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Target target = authorizeAndLock(connection, command.target());
                if (target == null) {
                    connection.commit();
                    return command.target() instanceof ProfileImageTarget.Account
                            ? ProfileImageMetadataResult.Rejected.ACCOUNT_UNAVAILABLE
                            : ProfileImageMetadataResult.Rejected.ROOM_ADMIN_REQUIRED;
                }
                if (!registerExactObject(connection, command.object())) {
                    connection.commit();
                    return ProfileImageMetadataResult.Rejected.OBJECT_EVIDENCE_CONFLICT;
                }
                Current current = lockCurrent(connection, target);
                if (current != null && current.objectKey().equals(command.object().objectKey())) {
                    if (current.width() != command.width() || current.height() != command.height())
                        throw new SQLException("profile image dimensions conflict with object");
                    var result = new ProfileImageMetadataResult.Committed(current.objectKey(),
                            current.version(), false, current.updatedAt().toInstant(),
                            Optional.empty(), Set.of());
                    connection.commit(); return result;
                }
                OffsetDateTime now = databaseNow(connection);
                long version = current == null ? 1 : Math.addExact(current.version(), 1);
                writeCurrent(connection, target, command, version, now);
                insertAudit(connection, target, current, command.object().objectKey(), version, now);
                Optional<String> cleanup = current == null ? Optional.empty()
                        : requestCleanupIfUnreferenced(connection, current.objectKey(), now);
                var result = new ProfileImageMetadataResult.Committed(
                        command.object().objectKey(), version, true, now.toInstant(), cleanup,
                        target.roomPeerAccountIds());
                connection.commit(); return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        }
    }

    private static Target authorizeAndLock(Connection connection, ProfileImageTarget target)
            throws SQLException {
        if (target instanceof ProfileImageTarget.Account account) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT account.id FROM chat.account account
                    JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                    WHERE account.id = ? AND account.disabled_at IS NULL
                    FOR UPDATE OF account
                    """)) {
                statement.setObject(1, account.actorAccountId());
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) return null;
                    UUID id = row.getObject(1, UUID.class);
                    if (row.next()) throw new SQLException("profile account mapping duplicated");
                    return new Target("ACCOUNT", id, id, Set.of());
                }
            }
        }
        ProfileImageTarget.LegacyRoom room = (ProfileImageTarget.LegacyRoom) target;
        UUID conversation;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation.id
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
                FOR UPDATE OF conversation, lifecycle, actor, account
                """)) {
            statement.setObject(1, room.actorAccountId()); statement.setLong(2, room.legacyRoomId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null; conversation = row.getObject(1, UUID.class);
                if (row.next()) throw new SQLException("profile room mapping duplicated");
            }
        }
        return new Target("GROUP", conversation, room.actorAccountId(),
                loadCompleteRoomPeers(connection, conversation, room.actorAccountId()));
    }

    private static Set<UUID> loadCompleteRoomPeers(Connection connection, UUID conversation,
            UUID actor) throws SQLException {
        LinkedHashSet<UUID> peers = new LinkedHashSet<>(); int count = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT member.account_id, mapping.legacy_user_id
                FROM chat.conversation_member member
                JOIN chat.account account ON account.id = member.account_id
                  AND account.disabled_at IS NULL
                LEFT JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE member.conversation_id = ? AND member.left_at IS NULL
                  AND member.account_id <> ?
                ORDER BY member.account_id FOR SHARE OF member, account
                """)) {
            statement.setObject(1, conversation); statement.setObject(2, actor);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    if (row.getObject("legacy_user_id") == null)
                        throw new SQLException("profile room audience mapping is incomplete");
                    if (!peers.add(row.getObject("account_id", UUID.class)))
                        throw new SQLException("profile room audience duplicated");
                    if (++count > MAX_AUDIENCE_MEMBERS)
                        throw new SQLException("profile room audience exceeds bound");
                }
            }
        }
        return Set.copyOf(peers);
    }

    private static boolean registerExactObject(Connection connection,
            ProfileImageObjectEvidence evidence) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO chat.profile_image_object
                    (object_key, byte_size, content_sha256, media_type)
                VALUES (?, ?, ?, ?) ON CONFLICT (object_key) DO NOTHING
                """)) {
            insert.setString(1, evidence.objectKey()); insert.setLong(2, evidence.byteSize());
            insert.setBytes(3, evidence.contentSha256()); insert.setString(4, evidence.mediaType());
            insert.executeUpdate();
        }
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT byte_size, content_sha256, media_type
                FROM chat.profile_image_object WHERE object_key = ? FOR UPDATE
                """)) {
            select.setString(1, evidence.objectKey());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) throw new SQLException("profile image object registration missing");
                boolean exact = row.getLong("byte_size") == evidence.byteSize()
                        && Arrays.equals(row.getBytes("content_sha256"), evidence.contentSha256())
                        && evidence.mediaType().equals(row.getString("media_type"));
                if (row.next()) throw new SQLException("profile image object duplicated");
                if (exact) clearCleanupState(connection, evidence.objectKey());
                return exact;
            }
        }
    }

    private static void clearCleanupState(Connection connection, String objectKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.profile_image_object SET cleanup_requested_at = NULL,
                    delete_confirmed_at = NULL WHERE object_key = ?
                """)) {
            statement.setString(1, objectKey); requireOne(statement, "profile object revival");
        }
    }

    private static Current lockCurrent(Connection connection, Target target) throws SQLException {
        String table = target.kind().equals("ACCOUNT")
                ? "chat.account_profile_image" : "chat.group_profile_image";
        String column = target.kind().equals("ACCOUNT") ? "account_id" : "conversation_id";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT object_key, width, height, version, updated_at FROM " + table
                        + " WHERE " + column + " = ? FOR UPDATE")) {
            statement.setObject(1, target.targetId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Current result = new Current(row.getString("object_key"), row.getInt("width"),
                        row.getInt("height"), row.getLong("version"),
                        row.getObject("updated_at", OffsetDateTime.class));
                if (row.next()) throw new SQLException("profile image pointer duplicated");
                return result;
            }
        }
    }

    private static void writeCurrent(Connection connection, Target target,
            ProfileImageMetadataCommand command, long version, OffsetDateTime now)
            throws SQLException {
        String table = target.kind().equals("ACCOUNT")
                ? "chat.account_profile_image" : "chat.group_profile_image";
        String column = target.kind().equals("ACCOUNT") ? "account_id" : "conversation_id";
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table + " (" + column
                + ", object_key, width, height, version, updated_at) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (" + column + ") DO UPDATE SET object_key = EXCLUDED.object_key, "
                + "width = EXCLUDED.width, height = EXCLUDED.height, "
                + "version = EXCLUDED.version, updated_at = EXCLUDED.updated_at")) {
            statement.setObject(1, target.targetId()); statement.setString(2, command.object().objectKey());
            statement.setInt(3, command.width()); statement.setInt(4, command.height());
            statement.setLong(5, version); statement.setObject(6, now);
            requireOne(statement, "profile image pointer update");
        }
    }

    private static void insertAudit(Connection connection, Target target, Current current,
            String objectKey, long version, OffsetDateTime now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.profile_image_change_audit
                    (id, target_kind, target_account_id, target_conversation_id,
                     actor_account_id, old_object_key, new_object_key, version, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID()); statement.setString(2, target.kind());
            statement.setObject(3, target.kind().equals("ACCOUNT") ? target.targetId() : null);
            statement.setObject(4, target.kind().equals("GROUP") ? target.targetId() : null);
            statement.setObject(5, target.actorAccountId());
            statement.setString(6, current == null ? null : current.objectKey());
            statement.setString(7, objectKey); statement.setLong(8, version);
            statement.setObject(9, now); requireOne(statement, "profile image audit");
        }
    }

    private static Optional<String> requestCleanupIfUnreferenced(Connection connection,
            String objectKey, OffsetDateTime now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.profile_image_object object SET cleanup_requested_at = ?
                WHERE object.object_key = ? AND object.cleanup_requested_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM chat.account_profile_image current
                                  WHERE current.object_key = object.object_key)
                  AND NOT EXISTS (SELECT 1 FROM chat.group_profile_image current
                                  WHERE current.object_key = object.object_key)
                """)) {
            statement.setObject(1, now); statement.setString(2, objectKey);
            return statement.executeUpdate() == 1 ? Optional.of(objectKey) : Optional.empty();
        }
    }

    private static OffsetDateTime databaseNow(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT transaction_timestamp()")) {
            if (!row.next()) throw new SQLException("database time unavailable");
            return row.getObject(1, OffsetDateTime.class);
        }
    }
    private static void requireOne(PreparedStatement statement, String operation)
            throws SQLException {
        if (statement.executeUpdate() != 1) throw new SQLException(operation + " failed");
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); } catch (SQLException rollback) { original.addSuppressed(rollback); }
    }
    private static boolean retryable(SQLException exception) {
        return "40001".equals(exception.getSQLState()) || "40P01".equals(exception.getSQLState());
    }
    private record Target(String kind, UUID targetId, UUID actorAccountId,
            Set<UUID> roomPeerAccountIds) { }
    private record Current(String objectKey, int width, int height, long version,
            OffsetDateTime updatedAt) { }
}
