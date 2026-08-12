package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1UserSearchEntry;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1UserSearchPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Enabled, mapped, literal-substring V1 account search. */
public final class PostgresLegacyV1UserSearchAdapter implements LegacyV1UserSearchPort {
    private final DataSource dataSource;

    public PostgresLegacyV1UserSearchAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public List<LegacyV1UserSearchEntry> search(
            UUID excludedAccountId, String literalKeyword, int limit) {
        Objects.requireNonNull(excludedAccountId, "excludedAccountId");
        Objects.requireNonNull(literalKeyword, "literalKeyword");
        if (literalKeyword.isBlank() || limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("invalid V1 user search request");
        }
        String pattern = "%" + escapeLike(literalKeyword) + "%";
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT account.id, mapping.legacy_user_id,
                           account.username_key, account.display_name
                    FROM chat.account account
                    JOIN chat.legacy_v1_account_map mapping
                      ON mapping.account_id = account.id
                    WHERE account.id <> ? AND account.disabled_at IS NULL
                      AND (account.username_key ILIKE ? ESCAPE '\\'
                           OR account.display_name ILIKE ? ESCAPE '\\')
                    ORDER BY account.username_key COLLATE "C", mapping.legacy_user_id
                    LIMIT ?
                    """)) {
                statement.setObject(1, excludedAccountId);
                statement.setString(2, pattern);
                statement.setString(3, pattern);
                statement.setInt(4, limit);
                try (ResultSet row = statement.executeQuery()) {
                    List<LegacyV1UserSearchEntry> result = new ArrayList<>();
                    while (row.next()) {
                        result.add(new LegacyV1UserSearchEntry(
                                row.getObject("id", UUID.class),
                                row.getLong("legacy_user_id"),
                                row.getString("username_key"),
                                row.getString("display_name")));
                    }
                    return List.copyOf(result);
                }
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException("V1 user search failed", exception);
        }
    }

    private static String escapeLike(String literal) {
        return literal.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
