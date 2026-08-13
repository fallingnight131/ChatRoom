package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import java.sql.*;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Serializable convergent V1 room administrator role mutation. */
public final class PostgresLegacyV1RoomAdminAdapter implements LegacyV1RoomAdminPort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1RoomAdminAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RoomAdminResult change(LegacyV1RoomAdminCommand command) {
        Objects.requireNonNull(command, "command");
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(command); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException("V1 room administrator change failed", last);
    }

    private LegacyV1RoomAdminResult attempt(LegacyV1RoomAdminCommand command)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                UUID conversation = lockActiveRoom(connection, command.legacyRoomId());
                if (conversation == null) return commit(connection,
                        LegacyV1RoomAdminResult.Rejected.ROOM_ADMIN_REQUIRED);
                Member actor = lockMemberByAccount(connection, conversation,
                        command.actorAccountId());
                if (actor == null || (actor.role() != Role.OWNER && actor.role() != Role.ADMIN)) {
                    return commit(connection,
                            LegacyV1RoomAdminResult.Rejected.ROOM_ADMIN_REQUIRED);
                }
                Member target = lockMemberByUsername(connection, conversation,
                        command.targetUsername());
                if (target == null) return commit(connection,
                        LegacyV1RoomAdminResult.Rejected.TARGET_NOT_ACTIVE_MEMBER);

                if (!command.admin()) {
                    if (!target.accountId().equals(command.actorAccountId())) {
                        return commit(connection,
                                LegacyV1RoomAdminResult.Rejected.SELF_DEMOTION_REQUIRED);
                    }
                    if (target.role() == Role.OWNER) return commit(connection,
                            LegacyV1RoomAdminResult.Rejected.OWNER_PROTECTED);
                    if (target.role() != Role.ADMIN) return commit(connection,
                            LegacyV1RoomAdminResult.Rejected.ROOM_ADMIN_REQUIRED);
                }

                Role desired = command.admin() && target.role() == Role.MEMBER
                        ? Role.ADMIN : command.admin() ? target.role() : Role.MEMBER;
                boolean changed = target.role() != desired;
                if (changed) updateRole(connection, conversation, target.accountId(),
                        target.role(), desired);
                touchConversation(connection, conversation, changed);
                LegacyV1RoomAdminResult result = new LegacyV1RoomAdminResult.Changed(
                        conversation, command.legacyRoomId(), target.accountId(),
                        target.username(), target.displayName(), command.admin(), changed);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private static UUID lockActiveRoom(Connection connection, long roomId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation.id
                FROM chat.legacy_v1_conversation_map mapping
                JOIN chat.conversation conversation
                  ON conversation.id = mapping.conversation_id
                 AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = conversation.id
                 AND lifecycle.closed_at IS NULL
                WHERE mapping.legacy_kind = 'ROOM'
                  AND mapping.legacy_conversation_id = ?
                FOR UPDATE OF conversation
                """)) {
            statement.setLong(1, roomId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                UUID result = row.getObject(1, UUID.class);
                if (row.next()) throw new SQLException("V1 administrator room duplicated");
                return result;
            }
        }
    }

    private static Member lockMemberByAccount(Connection connection, UUID conversation,
            UUID accountId) throws SQLException {
        return lockMember(connection, conversation, "account.id = ?", accountId);
    }

    private static Member lockMemberByUsername(Connection connection, UUID conversation,
            String username) throws SQLException {
        return lockMember(connection, conversation, "account.username_key = ?", username);
    }

    private static Member lockMember(Connection connection, UUID conversation,
            String predicate, Object value) throws SQLException {
        String sql = """
                SELECT account.id, account.username_key, account.display_name, member.role
                FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                JOIN chat.conversation_member member ON member.account_id = account.id
                 AND member.conversation_id = ? AND member.left_at IS NULL
                WHERE account.disabled_at IS NULL AND %s
                FOR UPDATE OF account, member
                """.formatted(predicate);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, conversation);
            statement.setObject(2, value);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                String username = row.getString("username_key");
                String display = row.getString("display_name");
                Member result = new Member(row.getObject("id", UUID.class), username,
                        display == null || display.isBlank() ? username : display,
                        Role.valueOf(row.getString("role")));
                if (row.next()) throw new SQLException("V1 administrator member duplicated");
                return result;
            }
        }
    }

    private static void updateRole(Connection connection, UUID conversation, UUID account,
            Role current, Role desired) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation_member SET role = ?
                WHERE conversation_id = ? AND account_id = ? AND role = ? AND left_at IS NULL
                """)) {
            statement.setString(1, desired.name());
            statement.setObject(2, conversation);
            statement.setObject(3, account);
            statement.setString(4, current.name());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("V1 administrator role changed concurrently");
            }
        }
    }

    private static void touchConversation(Connection connection, UUID conversation,
            boolean changed) throws SQLException {
        if (!changed) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chat.conversation SET updated_at = transaction_timestamp() WHERE id = ?")) {
            statement.setObject(1, conversation);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("V1 administrator conversation disappeared");
            }
        }
    }

    private static <T extends LegacyV1RoomAdminResult> T commit(
            Connection connection, T result) throws SQLException {
        connection.commit();
        return result;
    }

    private static boolean retryable(SQLException exception) {
        for (SQLException current = exception; current != null;
                current = current.getNextException()) {
            if ("40001".equals(current.getSQLState())
                    || "40P01".equals(current.getSQLState())) return true;
        }
        return false;
    }

    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }

    private enum Role { OWNER, ADMIN, MEMBER }
    private record Member(UUID accountId, String username, String displayName, Role role) { }
}
