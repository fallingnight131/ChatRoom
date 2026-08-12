package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1PendingFriendRequest;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1PendingFriendRequestPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Complete mapped PostgreSQL pending-request projection for V1 clients. */
public final class PostgresLegacyV1PendingFriendRequestAdapter
        implements LegacyV1PendingFriendRequestPort {
    private final DataSource dataSource;

    public PostgresLegacyV1PendingFriendRequestAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public List<LegacyV1PendingFriendRequest> listIncoming(
            UUID recipientAccountId, int maximumRows) {
        Objects.requireNonNull(recipientAccountId, "recipientAccountId");
        if (maximumRows < 1 || maximumRows > 10_000) {
            throw new IllegalArgumentException("maximumRows");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                long total = countPending(connection, recipientAccountId);
                if (total > maximumRows) {
                    throw new IllegalStateException("V1 pending requests exceed the fixed bound");
                }
                List<LegacyV1PendingFriendRequest> rows = readMapped(
                        connection, recipientAccountId, maximumRows);
                if (rows.size() != total) {
                    throw new IllegalStateException(
                            "V1 pending request compatibility projection is incomplete");
                }
                connection.commit();
                return rows;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException(
                    "V1 pending request read failed", exception);
        }
    }

    private static long countPending(Connection connection, UUID recipient) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*)
                FROM chat.contact_request request
                JOIN chat.account recipient ON recipient.id = request.recipient_account_id
                WHERE request.recipient_account_id = ?
                  AND request.state = 'PENDING'
                  AND recipient.disabled_at IS NULL
                """)) {
            statement.setObject(1, recipient);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("pending request count is absent");
                return result.getLong(1);
            }
        }
    }

    private static List<LegacyV1PendingFriendRequest> readMapped(
            Connection connection, UUID recipient, int maximumRows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT request_map.legacy_request_id,
                       requester_map.legacy_user_id,
                       requester.username_key,
                       requester.display_name,
                       request.created_at
                FROM chat.contact_request request
                JOIN chat.account requester
                  ON requester.id = request.requester_account_id
                 AND requester.disabled_at IS NULL
                JOIN chat.legacy_v1_contact_request_map request_map
                  ON request_map.contact_request_id = request.id
                JOIN chat.legacy_v1_account_map requester_map
                  ON requester_map.account_id = requester.id
                WHERE request.recipient_account_id = ? AND request.state = 'PENDING'
                ORDER BY request.created_at DESC, request.id DESC
                LIMIT ?
                """)) {
            statement.setObject(1, recipient);
            statement.setInt(2, maximumRows + 1);
            statement.setFetchSize(maximumRows + 1);
            List<LegacyV1PendingFriendRequest> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new LegacyV1PendingFriendRequest(
                            rows.getLong("legacy_request_id"),
                            rows.getLong("legacy_user_id"),
                            rows.getString("username_key"),
                            rows.getString("display_name"),
                            rows.getObject("created_at", OffsetDateTime.class).toInstant()));
                }
            }
            return List.copyOf(result);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException failure) {
            original.addSuppressed(failure);
        }
    }
}
