package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.contact.AccountBlockDirectoryPage;
import com.fallingnight.chat.application.contact.AccountBlockDirectoryPort;
import com.fallingnight.chat.application.contact.AccountBlockDirectoryQuery;
import com.fallingnight.chat.application.contact.AccountBlockDirectoryResult;
import com.fallingnight.chat.application.contact.AccountBlockSummary;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Repeatable-read authoritative outgoing-block directory. */
public final class PostgresAccountBlockDirectoryAdapter implements AccountBlockDirectoryPort {
    private static final String AUTHORIZED = "SELECT 1 FROM chat.account "
            + "WHERE id=? AND disabled_at IS NULL";
    private static final String SELECT = "SELECT ab.blocked_account_id, "
            + "target.display_name, ab.created_at "
            + "FROM chat.account_block ab "
            + "JOIN chat.account target ON target.id=ab.blocked_account_id "
            + "WHERE ab.blocker_account_id=? ";

    private final DataSource dataSource;

    public PostgresAccountBlockDirectoryAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public AccountBlockDirectoryResult list(AccountBlockDirectoryQuery query) {
        Objects.requireNonNull(query, "query");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                if (!authorized(connection, query.accountId())) {
                    connection.rollback();
                    return AccountBlockDirectoryResult.Rejected.NOT_AUTHORIZED;
                }
                List<AccountBlockSummary> blocks = read(connection, query);
                connection.commit();
                boolean hasMore = blocks.size() > query.limit();
                if (hasMore) blocks.removeLast();
                Optional<UUID> next = hasMore
                        ? Optional.of(blocks.getLast().targetAccountId())
                        : Optional.empty();
                return new AccountBlockDirectoryResult.Found(new AccountBlockDirectoryPage(
                        query.accountId(), blocks, next, hasMore));
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ContactPersistenceException(
                    "account block directory read failed", exception);
        }
    }

    private static boolean authorized(Connection connection, UUID accountId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(AUTHORIZED)) {
            statement.setObject(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static List<AccountBlockSummary> read(
            Connection connection, AccountBlockDirectoryQuery query) throws SQLException {
        boolean continued = query.afterTargetAccountId().isPresent();
        String sql = SELECT
                + (continued ? "AND ab.blocked_account_id > ? " : "")
                + "ORDER BY ab.blocked_account_id LIMIT ?";
        List<AccountBlockSummary> blocks = new ArrayList<>(query.limit() + 1);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setObject(parameter++, query.accountId());
            if (continued) {
                statement.setObject(parameter++, query.afterTargetAccountId().orElseThrow());
            }
            statement.setInt(parameter, query.limit() + 1);
            statement.setFetchSize(query.limit() + 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    blocks.add(new AccountBlockSummary(
                            result.getObject(1, UUID.class), result.getString(2),
                            result.getObject(3, OffsetDateTime.class).toInstant()));
                }
            }
        }
        return blocks;
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
