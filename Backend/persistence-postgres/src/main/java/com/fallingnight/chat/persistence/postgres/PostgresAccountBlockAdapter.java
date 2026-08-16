package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.contact.AccountBlockMutation;
import com.fallingnight.chat.application.contact.AccountBlockMutationPort;
import com.fallingnight.chat.application.contact.AccountBlockResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Atomic asymmetric account-block state and idempotency ledger adapter. */
public final class PostgresAccountBlockAdapter implements AccountBlockMutationPort {
    private final DataSource dataSource;

    public PostgresAccountBlockAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public AccountBlockResult apply(AccountBlockMutation mutation) {
        Objects.requireNonNull(mutation, "mutation");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                ExistingOperation existing = findExisting(connection, mutation);
                if (existing != null) {
                    connection.rollback();
                    return existingResult(mutation, existing);
                }
                if (!lockEnabledPair(connection, mutation)) {
                    connection.rollback();
                    return AccountBlockResult.Rejected.TARGET_UNAVAILABLE;
                }
                existing = findExisting(connection, mutation);
                if (existing != null) {
                    connection.rollback();
                    return existingResult(mutation, existing);
                }
                boolean currentlyBlocked = currentlyBlocked(connection, mutation);
                boolean changed = currentlyBlocked != mutation.blocked();
                if (changed) updateBlockState(connection, mutation);
                insertOperation(connection, mutation, changed);
                connection.commit();
                return applied(mutation, changed);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ContactPersistenceException("account block mutation failed", exception);
        }
    }

    private static ExistingOperation findExisting(
            Connection connection, AccountBlockMutation mutation) throws SQLException {
        String sql = "SELECT target_account_id, desired_blocked, changed "
                + "FROM chat.account_block_operation "
                + "WHERE actor_account_id=? AND client_operation_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, mutation.actorAccountId());
            statement.setObject(2, mutation.clientOperationId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new ExistingOperation(
                        result.getObject(1, UUID.class),
                        result.getBoolean(2), result.getBoolean(3)) : null;
            }
        }
    }

    private static AccountBlockResult existingResult(
            AccountBlockMutation mutation, ExistingOperation existing) {
        if (!existing.targetAccountId().equals(mutation.targetAccountId())
                || existing.blocked() != mutation.blocked()) {
            return new AccountBlockResult.OperationConflict(mutation.clientOperationId());
        }
        return applied(mutation, existing.changed());
    }

    private static boolean lockEnabledPair(
            Connection connection, AccountBlockMutation mutation) throws SQLException {
        String sql = "SELECT id, disabled_at IS NULL FROM chat.account "
                + "WHERE id IN (?, ?) ORDER BY id FOR UPDATE";
        Map<UUID, Boolean> enabled = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, mutation.actorAccountId());
            statement.setObject(2, mutation.targetAccountId());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    enabled.put(result.getObject(1, UUID.class), result.getBoolean(2));
                }
            }
        }
        return Boolean.TRUE.equals(enabled.get(mutation.actorAccountId()))
                && Boolean.TRUE.equals(enabled.get(mutation.targetAccountId()));
    }

    private static boolean currentlyBlocked(
            Connection connection, AccountBlockMutation mutation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM chat.account_block "
                        + "WHERE blocker_account_id=? AND blocked_account_id=?")) {
            statement.setObject(1, mutation.actorAccountId());
            statement.setObject(2, mutation.targetAccountId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void updateBlockState(
            Connection connection, AccountBlockMutation mutation) throws SQLException {
        String sql = mutation.blocked()
                ? "INSERT INTO chat.account_block(blocker_account_id, blocked_account_id) "
                        + "VALUES (?, ?)"
                : "DELETE FROM chat.account_block "
                        + "WHERE blocker_account_id=? AND blocked_account_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, mutation.actorAccountId());
            statement.setObject(2, mutation.targetAccountId());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("account block state changed while pair was locked");
            }
        }
    }

    private static void insertOperation(Connection connection, AccountBlockMutation mutation,
            boolean changed) throws SQLException {
        String sql = "INSERT INTO chat.account_block_operation(actor_account_id, "
                + "client_operation_id, target_account_id, desired_blocked, changed) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, mutation.actorAccountId());
            statement.setObject(2, mutation.clientOperationId());
            statement.setObject(3, mutation.targetAccountId());
            statement.setBoolean(4, mutation.blocked());
            statement.setBoolean(5, changed);
            statement.executeUpdate();
        }
    }

    private static AccountBlockResult.Applied applied(
            AccountBlockMutation mutation, boolean changed) {
        return new AccountBlockResult.Applied(
                mutation.actorAccountId(), mutation.targetAccountId(), mutation.blocked(),
                changed, mutation.clientOperationId());
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record ExistingOperation(UUID targetAccountId, boolean blocked, boolean changed) { }
}
