package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AccountIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1AccountProjectionPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** PostgreSQL projection for the temporary V1 account identity boundary. */
public final class PostgresLegacyV1AccountProjection
        implements LegacyV1AccountProjectionPort {
    private static final String BY_USERNAME = """
            SELECT mapping.legacy_user_id, mapping.account_id
            FROM chat.legacy_v1_account_map mapping
            JOIN chat.account account ON account.id = mapping.account_id
            WHERE account.username_key = ? AND account.disabled_at IS NULL
            """;
    private static final String BY_ACCOUNT_ID = """
            SELECT mapping.legacy_user_id, mapping.account_id
            FROM chat.legacy_v1_account_map mapping
            JOIN chat.account account ON account.id = mapping.account_id
            WHERE mapping.account_id = ? AND account.disabled_at IS NULL
            """;

    private final DataSource dataSource;

    public PostgresLegacyV1AccountProjection(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<LegacyV1AccountIdentity> findByPresentedUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return find(BY_USERNAME, statement -> statement.setString(1, username));
    }

    @Override
    public Optional<LegacyV1AccountIdentity> findByAccountId(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return find(BY_ACCOUNT_ID, statement -> statement.setObject(1, accountId));
    }

    @Override
    public Map<UUID, LegacyV1AccountIdentity> findByAccountIds(Set<UUID> accountIds) {
        Set<UUID> requested = Set.copyOf(accountIds);
        if (requested.isEmpty()) return Map.of();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT mapping.legacy_user_id, mapping.account_id
                        FROM chat.legacy_v1_account_map mapping
                        JOIN chat.account account ON account.id = mapping.account_id
                        WHERE mapping.account_id = ANY (?) AND account.disabled_at IS NULL
                        """)) {
            statement.setArray(1, connection.createArrayOf("uuid", requested.toArray()));
            Map<UUID, LegacyV1AccountIdentity> result = new LinkedHashMap<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID accountId = rows.getObject("account_id", UUID.class);
                    LegacyV1AccountIdentity identity = new LegacyV1AccountIdentity(
                            rows.getLong("legacy_user_id"), accountId);
                    if (result.put(accountId, identity) != null) {
                        throw new SQLException("V1 account batch projection returned duplicates");
                    }
                }
            }
            return Map.copyOf(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("V1 account projection failed", exception);
        }
    }

    private Optional<LegacyV1AccountIdentity> find(
            String sql, StatementBinder binder) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                LegacyV1AccountIdentity identity = new LegacyV1AccountIdentity(
                        result.getLong("legacy_user_id"),
                        result.getObject("account_id", UUID.class));
                if (result.next()) {
                    throw new SQLException("V1 account projection returned multiple rows");
                }
                return Optional.of(identity);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("V1 account projection failed", exception);
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
