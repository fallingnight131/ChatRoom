package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestCreationPort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestCreationResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Atomic, retry-convergent V1 friend-request creation. */
public final class PostgresLegacyV1FriendRequestCreationAdapter
        implements LegacyV1FriendRequestCreationPort {
    private static final int MAX_TRANSACTION_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1FriendRequestCreationAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public LegacyV1FriendRequestCreationResult create(
            UUID requesterAccountId, String targetUsername) {
        Objects.requireNonNull(requesterAccountId, "requesterAccountId");
        Objects.requireNonNull(targetUsername, "targetUsername");
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                return attempt(requesterAccountId, targetUsername);
            } catch (SQLException exception) {
                last = exception;
                if (!isRetryable(exception) || attempt == MAX_TRANSACTION_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException("V1 friend request creation failed", last);
    }

    private LegacyV1FriendRequestCreationResult attempt(
            UUID requesterAccountId, String targetUsername) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Account requester = lockRequester(connection, requesterAccountId);
                if (requester == null) {
                    connection.commit();
                    return LegacyV1FriendRequestCreationResult.Rejected.INVALID_TARGET;
                }
                Account recipient = findRecipient(connection, targetUsername);
                if (recipient == null) {
                    connection.commit();
                    return LegacyV1FriendRequestCreationResult.Rejected.USER_NOT_FOUND;
                }
                if (recipient.accountId().equals(requester.accountId())) {
                    connection.commit();
                    return LegacyV1FriendRequestCreationResult.Rejected.SELF_REQUEST;
                }
                if (areActiveFriends(connection, requester.accountId(), recipient.accountId())) {
                    connection.commit();
                    return LegacyV1FriendRequestCreationResult.Rejected.ALREADY_FRIENDS;
                }
                Pending pending = lockPendingPair(
                        connection, requester.accountId(), recipient.accountId());
                LegacyV1FriendRequestCreationResult result;
                if (pending != null) {
                    requireMappedPending(connection, pending.requestId());
                    result = pending.requesterAccountId().equals(requester.accountId())
                            ? new LegacyV1FriendRequestCreationResult.Accepted(
                                    true, recipient.accountId())
                            : LegacyV1FriendRequestCreationResult.Rejected.REVERSE_PENDING;
                } else {
                    UUID requestId = UUID.randomUUID();
                    insertRequest(connection, requestId, requester.accountId(), recipient.accountId());
                    insertMapping(connection, nextUnusedRequestId(connection), requestId);
                    result = new LegacyV1FriendRequestCreationResult.Accepted(
                            false, recipient.accountId());
                }
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private static Account lockRequester(Connection connection, UUID accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account.id
                FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE account.id = ? AND account.disabled_at IS NULL
                FOR SHARE OF account
                """)) {
            statement.setObject(1, accountId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new Account(row.getObject(1, UUID.class)) : null;
            }
        }
    }

    private static Account findRecipient(Connection connection, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account.id
                FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE account.username_key = ? AND account.disabled_at IS NULL
                FOR SHARE OF account
                """)) {
            statement.setString(1, username);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Account result = new Account(row.getObject(1, UUID.class));
                if (row.next()) throw new SQLException("target username returned duplicates");
                return result;
            }
        }
    }

    private static boolean areActiveFriends(
            Connection connection, UUID left, UUID right) throws SQLException {
        UUID first = min(left, right);
        UUID second = first.equals(left) ? right : left;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1 FROM chat.direct_conversation direct
                    JOIN chat.conversation_member first_member
                      ON first_member.conversation_id = direct.conversation_id
                     AND first_member.account_id = direct.first_account_id
                     AND first_member.left_at IS NULL
                    JOIN chat.conversation_member second_member
                      ON second_member.conversation_id = direct.conversation_id
                     AND second_member.account_id = direct.second_account_id
                     AND second_member.left_at IS NULL
                    WHERE direct.first_account_id = ? AND direct.second_account_id = ?)
                """)) {
            statement.setObject(1, first);
            statement.setObject(2, second);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("friendship check returned no row");
                return row.getBoolean(1);
            }
        }
    }

    private static Pending lockPendingPair(
            Connection connection, UUID left, UUID right) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, requester_account_id
                FROM chat.contact_request
                WHERE state = 'PENDING'
                  AND LEAST(requester_account_id, recipient_account_id) = LEAST(?, ?)
                  AND GREATEST(requester_account_id, recipient_account_id) = GREATEST(?, ?)
                FOR UPDATE
                """)) {
            statement.setObject(1, left);
            statement.setObject(2, right);
            statement.setObject(3, left);
            statement.setObject(4, right);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Pending result = new Pending(
                        row.getObject("id", UUID.class),
                        row.getObject("requester_account_id", UUID.class));
                if (row.next()) throw new SQLException("pending pair returned duplicates");
                return result;
            }
        }
    }

    private static void requireMappedPending(Connection connection, UUID requestId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT legacy_request_id FROM chat.legacy_v1_contact_request_map
                WHERE contact_request_id = ?
                """)) {
            statement.setObject(1, requestId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || row.getLong(1) <= 0 || row.getLong(1) > Integer.MAX_VALUE) {
                    throw new SQLException("pending request has no valid V1 mapping");
                }
                if (row.next()) throw new SQLException("pending request mapping returned duplicates");
            }
        }
    }

    private static void insertRequest(
            Connection connection, UUID requestId, UUID requester, UUID recipient)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.contact_request(
                    id, requester_account_id, recipient_account_id)
                VALUES (?, ?, ?)
                """)) {
            statement.setObject(1, requestId);
            statement.setObject(2, requester);
            statement.setObject(3, recipient);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("contact request insert affected unexpected rows");
            }
        }
    }

    private static long nextUnusedRequestId(Connection connection) throws SQLException {
        while (true) {
            long candidate;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT nextval('chat.legacy_v1_contact_request_id_seq')");
                    ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("request ID allocation returned no row");
                candidate = row.getLong(1);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT EXISTS (SELECT 1 FROM chat.legacy_v1_contact_request_map
                                   WHERE legacy_request_id = ?)
                    """)) {
                statement.setLong(1, candidate);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new SQLException("request ID occupancy returned no row");
                    if (!row.getBoolean(1)) return candidate;
                }
            }
        }
    }

    private static void insertMapping(
            Connection connection, long legacyId, UUID requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.legacy_v1_contact_request_map(
                    legacy_request_id, contact_request_id) VALUES (?, ?)
                """)) {
            statement.setLong(1, legacyId);
            statement.setObject(2, requestId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("contact request mapping affected unexpected rows");
            }
        }
    }

    private static boolean isRetryable(SQLException exception) {
        for (SQLException current = exception; current != null;
                current = current.getNextException()) {
            if ("40001".equals(current.getSQLState()) || "23505".equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static UUID min(UUID left, UUID right) {
        return left.toString().compareTo(right.toString()) <= 0 ? left : right;
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }
    private record Account(UUID accountId) { }
    private record Pending(UUID requestId, UUID requesterAccountId) { }
}
