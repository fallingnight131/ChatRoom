package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestDecisionPort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestRejectionResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Recipient-authorized transactional V1 contact-request decisions. */
public final class PostgresLegacyV1FriendRequestDecisionAdapter
        implements LegacyV1FriendRequestDecisionPort {
    private final DataSource dataSource;

    public PostgresLegacyV1FriendRequestDecisionAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public LegacyV1FriendRequestRejectionResult reject(
            long legacyRequestId, UUID recipientAccountId) {
        Objects.requireNonNull(recipientAccountId, "recipientAccountId");
        if (legacyRequestId <= 0) {
            return LegacyV1FriendRequestRejectionResult.Rejected.INSTANCE;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Target target = lockTarget(connection, legacyRequestId);
                LegacyV1FriendRequestRejectionResult result;
                if (target == null || !target.recipientAccountId().equals(recipientAccountId)) {
                    result = LegacyV1FriendRequestRejectionResult.Rejected.INSTANCE;
                } else if ("REJECTED".equals(target.state())) {
                    result = new LegacyV1FriendRequestRejectionResult.Accepted(true);
                } else if ("PENDING".equals(target.state())) {
                    transition(connection, target.requestId());
                    result = new LegacyV1FriendRequestRejectionResult.Accepted(false);
                } else {
                    result = LegacyV1FriendRequestRejectionResult.Rejected.INSTANCE;
                }
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException(
                    "V1 friend request rejection failed", exception);
        }
    }

    private static Target lockTarget(Connection connection, long legacyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT request.id, request.recipient_account_id, request.state
                FROM chat.legacy_v1_contact_request_map mapping
                JOIN chat.contact_request request ON request.id = mapping.contact_request_id
                JOIN chat.account recipient ON recipient.id = request.recipient_account_id
                WHERE mapping.legacy_request_id = ? AND recipient.disabled_at IS NULL
                FOR UPDATE OF request
                """)) {
            statement.setLong(1, legacyId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Target result = new Target(
                        row.getObject("id", UUID.class),
                        row.getObject("recipient_account_id", UUID.class),
                        row.getString("state"));
                if (row.next()) throw new SQLException("V1 request mapping returned duplicates");
                return result;
            }
        }
    }

    private static void transition(Connection connection, UUID requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.contact_request
                SET state = 'REJECTED', resolved_at = transaction_timestamp()
                WHERE id = ? AND state = 'PENDING'
                """)) {
            statement.setObject(1, requestId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("pending contact request transition was lost");
            }
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }

    private record Target(UUID requestId, UUID recipientAccountId, String state) { }
}
