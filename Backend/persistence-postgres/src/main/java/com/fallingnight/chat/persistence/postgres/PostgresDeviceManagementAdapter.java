package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.identity.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import javax.sql.DataSource;

/** Durable admission, bounded directory, and serializable other-device revocation. */
public final class PostgresDeviceManagementAdapter implements DeviceManagementPort {
    private static final int MAX_DEVICES = 100;
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresDeviceManagementAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public DeviceDirectoryResult listActive(AuthenticatedDeviceActor actor) {
        Objects.requireNonNull(actor, "actor");
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try {
                if (!actorActive(connection, actor, false)) {
                    connection.commit(); return DeviceDirectoryResult.Rejected.INSTANCE;
                }
                List<ManagedDevice> devices = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id, platform, created_at, last_seen_at
                        FROM chat.device
                        WHERE account_id = ? AND revoked_at IS NULL
                          AND platform IN ('WEB', 'WINDOWS')
                        ORDER BY last_seen_at DESC, id
                        LIMIT ?
                        """)) {
                    statement.setObject(1, actor.accountId());
                    statement.setInt(2, MAX_DEVICES + 1);
                    try (ResultSet row = statement.executeQuery()) {
                        while (row.next()) {
                            if (devices.size() == MAX_DEVICES)
                                throw new IdentityPersistenceException(
                                        "active device directory exceeds reviewed bound");
                            UUID id = row.getObject(1, UUID.class);
                            devices.add(new ManagedDevice(id,
                                    ClientPlatform.valueOf(row.getString(2)),
                                    row.getObject(3, OffsetDateTime.class).toInstant(),
                                    row.getObject(4, OffsetDateTime.class).toInstant(),
                                    id.equals(actor.deviceId())));
                        }
                    }
                }
                DeviceDirectoryResult result = new DeviceDirectoryResult.Available(devices);
                connection.commit(); return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        } catch (SQLException exception) {
            throw new IdentityPersistenceException("active device directory failed", exception);
        }
    }

    @Override public DeviceRevocationResult revokeOther(
            AuthenticatedDeviceActor actor, UUID targetDeviceId) {
        Objects.requireNonNull(actor, "actor"); Objects.requireNonNull(targetDeviceId, "target");
        if (actor.deviceId().equals(targetDeviceId))
            return DeviceRevocationResult.Rejected.INSTANCE;
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return revokeAttempt(actor, targetDeviceId); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new IdentityPersistenceException("device revocation failed", last);
    }

    private DeviceRevocationResult revokeAttempt(
            AuthenticatedDeviceActor actor, UUID targetDeviceId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                if (!actorActive(connection, actor, true)) {
                    connection.commit(); return DeviceRevocationResult.Rejected.INSTANCE;
                }
                Target target = lockTarget(connection, actor.accountId(), targetDeviceId);
                if (target == null) {
                    connection.commit(); return DeviceRevocationResult.Rejected.INSTANCE;
                }
                if (target.revokedAt() != null) {
                    DeviceRevocationResult retained = retainedAudit(
                            connection, actor.accountId(), targetDeviceId);
                    connection.commit(); return retained;
                }
                OffsetDateTime now = databaseNow(connection);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE chat.device SET revoked_at = ?
                        WHERE id = ? AND account_id = ? AND revoked_at IS NULL
                        """)) {
                    statement.setObject(1, now); statement.setObject(2, targetDeviceId);
                    statement.setObject(3, actor.accountId()); requireOne(statement, "device revoke");
                }
                int sessions;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE chat.device_session SET revoked_at = ?
                        WHERE account_id = ? AND device_id = ? AND revoked_at IS NULL
                        """)) {
                    statement.setObject(1, now); statement.setObject(2, actor.accountId());
                    statement.setObject(3, targetDeviceId); sessions = statement.executeUpdate();
                }
                UUID auditId = UUID.randomUUID();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO chat.device_revocation_audit
                            (id, account_id, target_device_id, actor_device_id,
                             actor_session_id, revoked_sessions, reason, occurred_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'USER_REQUEST', ?)
                        """)) {
                    statement.setObject(1, auditId); statement.setObject(2, actor.accountId());
                    statement.setObject(3, targetDeviceId); statement.setObject(4, actor.deviceId());
                    statement.setObject(5, actor.sessionId()); statement.setInt(6, sessions);
                    statement.setObject(7, now); requireOne(statement, "device revocation audit");
                }
                connection.commit();
                return new DeviceRevocationResult.Revoked(targetDeviceId, auditId,
                        now.toInstant(), sessions, true);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        }
    }

    private static boolean actorActive(Connection connection,
            AuthenticatedDeviceActor actor, boolean lock) throws SQLException {
        String sql = """
                SELECT 1 FROM chat.account account
                JOIN chat.device device ON device.id = ? AND device.account_id = account.id
                JOIN chat.device_session session ON session.id = ?
                  AND session.account_id = account.id AND session.device_id = device.id
                WHERE account.id = ? AND account.disabled_at IS NULL
                  AND device.revoked_at IS NULL AND session.revoked_at IS NULL
                  AND session.expires_at > transaction_timestamp()
                """ + (lock ? " FOR UPDATE OF account, device, session" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, actor.deviceId()); statement.setObject(2, actor.sessionId());
            statement.setObject(3, actor.accountId());
            try (ResultSet row = statement.executeQuery()) {
                boolean found = row.next();
                if (found && row.next()) throw new SQLException("device actor duplicated");
                return found;
            }
        }
    }

    private static Target lockTarget(Connection connection, UUID account, UUID target)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT revoked_at FROM chat.device
                WHERE id = ? AND account_id = ? AND platform IN ('WEB', 'WINDOWS')
                FOR UPDATE
                """)) {
            statement.setObject(1, target); statement.setObject(2, account);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Target result = new Target(row.getObject(1, OffsetDateTime.class));
                if (row.next()) throw new SQLException("target device duplicated");
                return result;
            }
        }
    }

    private static DeviceRevocationResult retainedAudit(
            Connection connection, UUID account, UUID target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, occurred_at, revoked_sessions
                FROM chat.device_revocation_audit
                WHERE account_id = ? AND target_device_id = ?
                """)) {
            statement.setObject(1, account); statement.setObject(2, target);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return DeviceRevocationResult.Rejected.INSTANCE;
                var result = new DeviceRevocationResult.Revoked(target,
                        row.getObject(1, UUID.class),
                        row.getObject(2, OffsetDateTime.class).toInstant(),
                        row.getInt(3), false);
                if (row.next()) throw new SQLException("device revocation audit duplicated");
                return result;
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
    private static void requireOne(PreparedStatement statement, String operation)
            throws SQLException {
        if (statement.executeUpdate() != 1) throw new SQLException(operation + " failed");
    }
    private static boolean retryable(SQLException exception) {
        return "40001".equals(exception.getSQLState()) || "40P01".equals(exception.getSQLState());
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException rollback) { original.addSuppressed(rollback); }
    }
    private record Target(OffsetDateTime revokedAt) { }
}
