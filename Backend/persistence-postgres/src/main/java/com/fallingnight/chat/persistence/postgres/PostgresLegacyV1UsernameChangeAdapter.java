package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import java.sql.*;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import javax.sql.DataSource;

/** Serializable V1 login-name mutation with durable cooldown and peer effects. */
public final class PostgresLegacyV1UsernameChangeAdapter
        implements LegacyV1UsernameChangePort {
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ROOMS = 1_000;
    private static final int MAX_AUDIENCE_MEMBERS = 100_000;
    private static final Duration COOLDOWN = Duration.ofDays(30);
    private final DataSource dataSource;

    public PostgresLegacyV1UsernameChangeAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1UsernameChangeResult change(
            LegacyV1UsernameChangeCommand command) {
        Objects.requireNonNull(command, "command"); SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(command); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException("V1 username change failed", last);
    }

    private LegacyV1UsernameChangeResult attempt(LegacyV1UsernameChangeCommand command)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Account account = lockAccount(connection, command.actorAccountId());
                if (account == null) {
                    connection.commit();
                    return LegacyV1UsernameChangeResult.Rejected.ACCOUNT_UNAVAILABLE;
                }
                if (account.username().equals(command.newUsername())) {
                    OffsetDateTime prior = lastMatchingChange(connection, account.id(),
                            command.newUsername());
                    LegacyV1UsernameChangeResult result = prior == null
                            ? LegacyV1UsernameChangeResult.Rejected.SAME_AS_CURRENT
                            : changed(account, command.newUsername(), false, prior, List.of());
                    connection.commit(); return result;
                }
                OffsetDateTime now = databaseNow(connection);
                if (account.usernameChangedAt() != null) {
                    OffsetDateTime retryAt = account.usernameChangedAt().plus(COOLDOWN);
                    if (now.isBefore(retryAt)) {
                        connection.commit();
                        return new LegacyV1UsernameChangeResult.Cooldown(retryAt.toInstant());
                    }
                }
                if (usernameExists(connection, command.newUsername())) {
                    connection.commit();
                    return LegacyV1UsernameChangeResult.Rejected.USERNAME_TAKEN;
                }
                List<LegacyV1UsernameChangeResult.RoomAudience> audiences =
                        loadPeerAudiences(connection, account.id());
                updateAccount(connection, account.id(), command.newUsername(), now);
                insertAudit(connection, account, command.newUsername(), now);
                LegacyV1UsernameChangeResult result = changed(
                        account, command.newUsername(), true, now, audiences);
                connection.commit(); return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        }
    }

    private static Account lockAccount(Connection connection, UUID accountId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account.id, account.username_key, account.username_changed_at
                FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE account.id = ? AND account.disabled_at IS NULL
                FOR UPDATE OF account
                """)) {
            statement.setObject(1, accountId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Account result = new Account(row.getObject("id", UUID.class),
                        row.getString("username_key"),
                        row.getObject("username_changed_at", OffsetDateTime.class));
                if (row.next()) throw new SQLException("V1 account mapping duplicated");
                return result;
            }
        }
    }

    private static OffsetDateTime lastMatchingChange(Connection connection, UUID accountId,
            String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT new_username, occurred_at
                FROM chat.account_username_change_audit
                WHERE account_id = ? ORDER BY occurred_at DESC, id DESC LIMIT 1
                """)) {
            statement.setObject(1, accountId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !username.equals(row.getString("new_username"))) return null;
                return row.getObject("occurred_at", OffsetDateTime.class);
            }
        }
    }

    private static boolean usernameExists(Connection connection, String username)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM chat.account WHERE username_key = ? FOR SHARE
                """)) {
            statement.setString(1, username);
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }

    private static List<LegacyV1UsernameChangeResult.RoomAudience> loadPeerAudiences(
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
                 AND member.left_at IS NULL AND member.account_id <> ?
                JOIN chat.account member_account
                  ON member_account.id = member.account_id
                 AND member_account.disabled_at IS NULL
                LEFT JOIN chat.legacy_v1_account_map account_map
                  ON account_map.account_id = member.account_id
                WHERE actor_member.account_id = ? AND actor_member.left_at IS NULL
                ORDER BY room_map.legacy_conversation_id, member.account_id
                """;
        LinkedHashMap<Long, LinkedHashSet<UUID>> rooms = new LinkedHashMap<>(); int members = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, actor); statement.setObject(2, actor);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    if (row.getObject("legacy_user_id") == null)
                        throw new SQLException("V1 username audience mapping is incomplete");
                    long roomId = row.getLong("legacy_conversation_id");
                    LinkedHashSet<UUID> audience = rooms.computeIfAbsent(
                            roomId, ignored -> new LinkedHashSet<>());
                    if (rooms.size() > MAX_ROOMS)
                        throw new SQLException("V1 username room fan-out exceeds bound");
                    if (!audience.add(row.getObject("account_id", UUID.class)))
                        throw new SQLException("V1 username audience duplicated");
                    if (++members > MAX_AUDIENCE_MEMBERS)
                        throw new SQLException("V1 username recipient fan-out exceeds bound");
                }
            }
        }
        ArrayList<LegacyV1UsernameChangeResult.RoomAudience> result = new ArrayList<>();
        rooms.forEach((roomId, audience) -> result.add(
                new LegacyV1UsernameChangeResult.RoomAudience(roomId, audience)));
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
            String username, OffsetDateTime occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.account SET username_key = ?, username_changed_at = ?,
                    profile_updated_at = ? WHERE id = ? AND disabled_at IS NULL
                """)) {
            statement.setString(1, username); statement.setObject(2, occurredAt);
            statement.setObject(3, occurredAt); statement.setObject(4, accountId);
            requireOne(statement, "username update");
        }
    }

    private static void insertAudit(Connection connection, Account account,
            String username, OffsetDateTime occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.account_username_change_audit
                    (id, account_id, old_username, new_username, occurred_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID()); statement.setObject(2, account.id());
            statement.setString(3, account.username()); statement.setString(4, username);
            statement.setObject(5, occurredAt); requireOne(statement, "username audit");
        }
    }

    private static LegacyV1UsernameChangeResult.Changed changed(Account account,
            String username, boolean changed, OffsetDateTime occurredAt,
            List<LegacyV1UsernameChangeResult.RoomAudience> audiences) {
        return new LegacyV1UsernameChangeResult.Changed(account.id(),
                changed ? account.username() : username, username, changed,
                occurredAt.toInstant(), occurredAt.plus(COOLDOWN).toInstant(), audiences);
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
    private record Account(UUID id, String username, OffsetDateTime usernameChangedAt) {}
}
