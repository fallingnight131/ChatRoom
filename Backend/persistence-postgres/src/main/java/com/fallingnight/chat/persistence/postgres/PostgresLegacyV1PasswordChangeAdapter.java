package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import com.fallingnight.chat.application.identity.StoredCredential;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Current-session-bound serializable password replacement and audit adapter. */
public final class PostgresLegacyV1PasswordChangeAdapter
        implements LegacyV1PasswordChangePort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1PasswordChangeAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1PasswordChangeAccess inspect(
            UUID actorAccountId, UUID currentSessionId) {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(currentSessionId, "currentSessionId");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(accessSql(false))) {
            statement.setObject(1, currentSessionId); statement.setObject(2, actorAccountId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return LegacyV1PasswordChangeAccess.Rejected.SESSION_INVALID;
                var result = new LegacyV1PasswordChangeAccess.Candidate(credential(row),
                        row.getObject("password_changed_at", OffsetDateTime.class).toInstant());
                if (row.next()) throw new SQLException("V1 password access duplicated");
                return result;
            }
        } catch (SQLException exception) {
            throw new IdentityPersistenceException("V1 password inspection failed", exception);
        }
    }

    @Override public LegacyV1PasswordChangePersistenceResult replace(
            LegacyV1PasswordChangeIntent intent) {
        Objects.requireNonNull(intent, "intent"); SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(intent); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new IdentityPersistenceException("V1 password replacement failed", last);
    }

    private LegacyV1PasswordChangePersistenceResult attempt(
            LegacyV1PasswordChangeIntent intent) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Candidate candidate = lockCandidate(connection,
                        intent.actorAccountId(), intent.currentSessionId());
                if (candidate == null) {
                    connection.commit();
                    return LegacyV1PasswordChangePersistenceResult.Rejected.SESSION_INVALID;
                }
                if (!candidate.credential().equals(intent.expectedCredential())) {
                    connection.commit();
                    return LegacyV1PasswordChangePersistenceResult.Rejected.CONCURRENT_CHANGE;
                }
                OffsetDateTime changedAt = candidate.databaseNow();
                updateCredential(connection, intent.actorAccountId(),
                        intent.replacementCredential(), changedAt);
                int revoked = revokeOtherSessions(connection, intent.actorAccountId(),
                        intent.currentSessionId(), changedAt);
                insertAudit(connection, intent, revoked, changedAt);
                connection.commit();
                return new LegacyV1PasswordChangePersistenceResult.Updated(
                        revoked, changedAt.toInstant());
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        }
    }

    private static Candidate lockCandidate(Connection connection, UUID account, UUID session)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(accessSql(true))) {
            statement.setObject(1, session); statement.setObject(2, account);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Candidate result = new Candidate(credential(row),
                        row.getObject("database_now", OffsetDateTime.class));
                if (row.next()) throw new SQLException("V1 password candidate duplicated");
                return result;
            }
        }
    }

    private static String accessSql(boolean locking) {
        return """
                SELECT account.password_hash, account.password_scheme,
                       account.legacy_password_salt, account.password_changed_at,
                       transaction_timestamp() AS database_now
                FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                JOIN chat.device_session session ON session.account_id = account.id
                  AND session.id = ?
                JOIN chat.device device ON device.id = session.device_id
                  AND device.account_id = account.id
                WHERE account.id = ? AND account.disabled_at IS NULL
                  AND device.revoked_at IS NULL AND session.revoked_at IS NULL
                  AND session.expires_at > transaction_timestamp()
                """ + (locking ? " FOR UPDATE OF account, session, device" : "");
    }

    private static StoredCredential credential(ResultSet row) throws SQLException {
        String hash = row.getString("password_hash");
        return switch (row.getString("password_scheme")) {
            case "ARGON2ID" -> new StoredCredential.Argon2id(hash);
            case "V1_SHA256" -> new StoredCredential.LegacySha256(
                    hash, row.getString("legacy_password_salt"));
            default -> throw new SQLException("unsupported password scheme");
        };
    }

    private static void updateCredential(Connection connection, UUID account,
            StoredCredential.Argon2id replacement, OffsetDateTime changedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.account SET password_hash = ?, password_scheme = 'ARGON2ID',
                    legacy_password_salt = NULL, password_changed_at = ? WHERE id = ?
                """)) {
            statement.setString(1, replacement.encodedHash());
            statement.setObject(2, changedAt); statement.setObject(3, account);
            requireOne(statement, "V1 password replacement");
        }
    }

    private static int revokeOtherSessions(Connection connection, UUID account,
            UUID retainedSession, OffsetDateTime changedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.device_session SET revoked_at = ?
                WHERE account_id = ? AND id <> ? AND revoked_at IS NULL
                  AND expires_at > ?
                """)) {
            statement.setObject(1, changedAt); statement.setObject(2, account);
            statement.setObject(3, retainedSession); statement.setObject(4, changedAt);
            return statement.executeUpdate();
        }
    }

    private static void insertAudit(Connection connection, LegacyV1PasswordChangeIntent intent,
            int revoked, OffsetDateTime changedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.account_password_change_audit
                    (id, account_id, initiating_session_id, other_sessions_revoked, occurred_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, intent.actorAccountId());
            statement.setObject(3, intent.currentSessionId());
            statement.setInt(4, revoked); statement.setObject(5, changedAt);
            requireOne(statement, "V1 password audit");
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
    private record Candidate(StoredCredential credential, OffsetDateTime databaseNow) { }
}
