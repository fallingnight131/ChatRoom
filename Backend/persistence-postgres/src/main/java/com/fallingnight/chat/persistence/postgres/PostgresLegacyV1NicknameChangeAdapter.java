package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import javax.sql.DataSource;

/** Serializable account profile mutation with complete V1 room effect intent. */
public final class PostgresLegacyV1NicknameChangeAdapter
        implements LegacyV1NicknameChangePort {
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ROOMS = 1_000;
    private static final int MAX_AUDIENCE_MEMBERS = 100_000;
    private final DataSource dataSource;

    public PostgresLegacyV1NicknameChangeAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1NicknameChangeResult change(
            LegacyV1NicknameChangeCommand command) {
        Objects.requireNonNull(command, "command");
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(command); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException("V1 nickname change failed", last);
    }

    private LegacyV1NicknameChangeResult attempt(LegacyV1NicknameChangeCommand command)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Account account = lockAccount(connection, command.actorAccountId());
                if (account == null) {
                    connection.commit();
                    return LegacyV1NicknameChangeResult.Rejected.ACCOUNT_UNAVAILABLE;
                }
                if (account.displayName().equals(command.newDisplayName())) {
                    var result = changed(account, command.newDisplayName(), false,
                            account.profileUpdatedAt(), List.of());
                    connection.commit();
                    return result;
                }
                List<LegacyV1NicknameChangeResult.RoomAudience> audiences =
                        loadRoomAudiences(connection, command.actorAccountId());
                OffsetDateTime occurredAt = databaseNow(connection);
                updateAccount(connection, account.id(), command.newDisplayName(), occurredAt);
                insertAudit(connection, account, command.newDisplayName(), occurredAt);
                var result = changed(account, command.newDisplayName(), true,
                        occurredAt, audiences);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private static Account lockAccount(Connection connection, UUID accountId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account.id, account.display_name, account.profile_updated_at
                FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE account.id = ? AND account.disabled_at IS NULL
                FOR UPDATE OF account
                """)) {
            statement.setObject(1, accountId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Account result = new Account(row.getObject("id", UUID.class),
                        row.getString("display_name"),
                        row.getObject("profile_updated_at", OffsetDateTime.class));
                if (row.next()) throw new SQLException("V1 account mapping duplicated");
                return result;
            }
        }
    }

    private static List<LegacyV1NicknameChangeResult.RoomAudience> loadRoomAudiences(
            Connection connection, UUID actor) throws SQLException {
        String sql = """
                SELECT room_map.legacy_conversation_id, member.account_id,
                       account_map.legacy_user_id
                FROM chat.conversation_member actor_member
                JOIN chat.conversation conversation
                  ON conversation.id = actor_member.conversation_id
                 AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = conversation.id
                 AND lifecycle.closed_at IS NULL
                JOIN chat.legacy_v1_conversation_map room_map
                  ON room_map.conversation_id = conversation.id
                 AND room_map.legacy_kind = 'ROOM'
                JOIN chat.conversation_member member
                  ON member.conversation_id = conversation.id
                 AND member.left_at IS NULL
                JOIN chat.account member_account
                  ON member_account.id = member.account_id
                 AND member_account.disabled_at IS NULL
                LEFT JOIN chat.legacy_v1_account_map account_map
                  ON account_map.account_id = member.account_id
                WHERE actor_member.account_id = ? AND actor_member.left_at IS NULL
                ORDER BY room_map.legacy_conversation_id, member.account_id
                """;
        LinkedHashMap<Long, LinkedHashSet<UUID>> rooms = new LinkedHashMap<>();
        int members = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, actor);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    if (row.getObject("legacy_user_id") == null)
                        throw new SQLException("V1 nickname audience mapping is incomplete");
                    long roomId = row.getLong("legacy_conversation_id");
                    LinkedHashSet<UUID> audience = rooms.computeIfAbsent(
                            roomId, ignored -> new LinkedHashSet<>());
                    if (rooms.size() > MAX_ROOMS)
                        throw new SQLException("V1 nickname room fan-out exceeds bound");
                    if (!audience.add(row.getObject("account_id", UUID.class)))
                        throw new SQLException("V1 nickname audience duplicated");
                    if (++members > MAX_AUDIENCE_MEMBERS)
                        throw new SQLException("V1 nickname recipient fan-out exceeds bound");
                }
            }
        }
        ArrayList<LegacyV1NicknameChangeResult.RoomAudience> result = new ArrayList<>();
        rooms.forEach((roomId, audience) -> result.add(
                new LegacyV1NicknameChangeResult.RoomAudience(roomId, audience)));
        return List.copyOf(result);
    }

    private static OffsetDateTime databaseNow(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT transaction_timestamp()")) {
            if (!row.next()) throw new SQLException("database time unavailable");
            return row.getObject(1, OffsetDateTime.class);
        }
    }

    private static void updateAccount(Connection connection, UUID accountId,
            String displayName, OffsetDateTime occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.account SET display_name = ?, profile_updated_at = ?
                WHERE id = ? AND disabled_at IS NULL
                """)) {
            statement.setString(1, displayName); statement.setObject(2, occurredAt);
            statement.setObject(3, accountId); requireOne(statement, "nickname update");
        }
    }

    private static void insertAudit(Connection connection, Account account,
            String displayName, OffsetDateTime occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.account_display_name_change_audit
                    (id, account_id, old_display_name, new_display_name, occurred_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID()); statement.setObject(2, account.id());
            statement.setString(3, account.displayName()); statement.setString(4, displayName);
            statement.setObject(5, occurredAt); requireOne(statement, "nickname audit");
        }
    }

    private static LegacyV1NicknameChangeResult.Changed changed(Account account,
            String displayName, boolean changed, OffsetDateTime occurredAt,
            List<LegacyV1NicknameChangeResult.RoomAudience> audiences) {
        return new LegacyV1NicknameChangeResult.Changed(account.id(), account.displayName(),
                displayName, changed, occurredAt.toInstant(), audiences);
    }

    private static void requireOne(PreparedStatement statement, String operation)
            throws SQLException {
        if (statement.executeUpdate() != 1) throw new SQLException(operation + " failed");
    }

    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException rollback) { original.addSuppressed(rollback); }
    }

    private static boolean retryable(SQLException exception) {
        return "40001".equals(exception.getSQLState()) || "40P01".equals(exception.getSQLState());
    }

    private record Account(UUID id, String displayName, OffsetDateTime profileUpdatedAt) {}
}
