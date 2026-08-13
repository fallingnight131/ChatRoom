package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import com.fallingnight.chat.application.identity.StoredCredential;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import javax.sql.DataSource;

/** Serialized natural-key V1 registration with exact compatibility ID allocation. */
public final class PostgresLegacyV1RegistrationAdapter implements LegacyV1RegistrationPort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1RegistrationAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RegistrationPersistenceResult register(
            LegacyV1RegistrationIntent intent) {
        Objects.requireNonNull(intent, "intent"); SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(intent); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new IdentityPersistenceException("V1 registration failed", last);
    }

    private LegacyV1RegistrationPersistenceResult attempt(LegacyV1RegistrationIntent intent)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                lockUsername(connection, intent.username());
                LegacyV1RegistrationPersistenceResult.Existing existing =
                        existing(connection, intent.username());
                if (existing != null) { connection.commit(); return existing; }
                long legacyUserId = nextUnusedUserId(connection);
                UUID accountId = UUID.randomUUID(); OffsetDateTime createdAt = databaseNow(connection);
                insertAccount(connection, accountId, intent, createdAt);
                insertMapping(connection, accountId, legacyUserId);
                insertAudit(connection, accountId, legacyUserId, createdAt);
                connection.commit();
                return new LegacyV1RegistrationPersistenceResult.Created(
                        accountId, legacyUserId, createdAt.toInstant());
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        }
    }

    private static void lockUsername(Connection connection, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 763281))")) {
            statement.setString(1, username);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("username lock unavailable");
            }
        }
    }

    private static LegacyV1RegistrationPersistenceResult.Existing existing(
            Connection connection, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account.id, account.username_key, account.display_name,
                       account.password_hash, account.password_scheme,
                       account.legacy_password_salt, account.created_at,
                       mapping.legacy_user_id
                FROM chat.account account
                LEFT JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE account.username_key = ?
                FOR UPDATE OF account
                """)) {
            statement.setString(1, username);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Long legacy = row.getObject("legacy_user_id", Long.class);
                var result = new LegacyV1RegistrationPersistenceResult.Existing(
                        row.getObject("id", UUID.class), legacy == null
                            ? OptionalLong.empty() : OptionalLong.of(legacy),
                        row.getString("username_key"), row.getString("display_name"),
                        credential(row), row.getObject("created_at", OffsetDateTime.class).toInstant());
                if (row.next()) throw new SQLException("registration username duplicated");
                return result;
            }
        }
    }

    private static StoredCredential credential(ResultSet row) throws SQLException {
        return switch (row.getString("password_scheme")) {
            case "ARGON2ID" -> new StoredCredential.Argon2id(row.getString("password_hash"));
            case "V1_SHA256" -> new StoredCredential.LegacySha256(
                    row.getString("password_hash"), row.getString("legacy_password_salt"));
            default -> throw new SQLException("unsupported registration credential scheme");
        };
    }
    private static long nextUnusedUserId(Connection connection) throws SQLException {
        while (true) {
            long candidate;
            try (Statement statement = connection.createStatement();
                    ResultSet row = statement.executeQuery(
                            "SELECT nextval('chat.legacy_v1_user_id_seq')")) {
                if (!row.next()) throw new SQLException("V1 user ID allocation unavailable");
                candidate = row.getLong(1);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM chat.legacy_v1_account_map "
                            + "WHERE legacy_user_id = ?)")) {
                statement.setLong(1, candidate);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new SQLException("V1 user ID occupancy unavailable");
                    if (!row.getBoolean(1)) return candidate;
                }
            }
        }
    }
    private static OffsetDateTime databaseNow(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT transaction_timestamp()")) {
            if (!row.next()) throw new SQLException("database time unavailable");
            return row.getObject(1, OffsetDateTime.class);
        }
    }
    private static void insertAccount(Connection connection, UUID account,
            LegacyV1RegistrationIntent intent, OffsetDateTime createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.account(id, username_key, display_name, password_hash,
                    password_scheme, legacy_password_salt, created_at, password_changed_at)
                VALUES (?, ?, ?, ?, 'ARGON2ID', NULL, ?, ?)
                """)) {
            statement.setObject(1, account); statement.setString(2, intent.username());
            statement.setString(3, intent.displayName());
            statement.setString(4, intent.credential().encodedHash());
            statement.setObject(5, createdAt); statement.setObject(6, createdAt);
            requireOne(statement, "V1 registration account");
        }
    }
    private static void insertMapping(Connection connection, UUID account, long legacyUserId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) VALUES (?, ?)")) {
            statement.setLong(1, legacyUserId); statement.setObject(2, account);
            requireOne(statement, "V1 registration mapping");
        }
    }
    private static void insertAudit(Connection connection, UUID account, long legacyUserId,
            OffsetDateTime registeredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.legacy_v1_registration_audit(
                    account_id, legacy_user_id, registered_at) VALUES (?, ?, ?)
                """)) {
            statement.setObject(1, account); statement.setLong(2, legacyUserId);
            statement.setObject(3, registeredAt); requireOne(statement, "V1 registration audit");
        }
    }
    private static void requireOne(PreparedStatement statement, String operation)
            throws SQLException {
        if (statement.executeUpdate() != 1)
            throw new SQLException(operation + " affected unexpected rows");
    }
    private static boolean retryable(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException())
            if ("40001".equals(current.getSQLState())) return true;
        return false;
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }
}
