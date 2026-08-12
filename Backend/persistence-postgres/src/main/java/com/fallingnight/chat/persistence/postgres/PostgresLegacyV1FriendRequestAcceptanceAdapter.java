package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestAcceptancePort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestAcceptanceResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Atomic canonical DIRECT establishment for accepted V1 contact requests. */
public final class PostgresLegacyV1FriendRequestAcceptanceAdapter
        implements LegacyV1FriendRequestAcceptancePort {
    private final DataSource dataSource;

    public PostgresLegacyV1FriendRequestAcceptanceAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public LegacyV1FriendRequestAcceptanceResult accept(
            long legacyRequestId, UUID recipientAccountId) {
        Objects.requireNonNull(recipientAccountId, "recipientAccountId");
        if (legacyRequestId <= 0 || legacyRequestId > Integer.MAX_VALUE) {
            return LegacyV1FriendRequestAcceptanceResult.Rejected.INSTANCE;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Target target = lockTarget(connection, legacyRequestId);
                LegacyV1FriendRequestAcceptanceResult result;
                if (target == null || !target.recipientAccountId().equals(recipientAccountId)) {
                    result = LegacyV1FriendRequestAcceptanceResult.Rejected.INSTANCE;
                } else if ("ACCEPTED".equals(target.state())) {
                    requireCompleteRelationship(connection, target);
                    result = new LegacyV1FriendRequestAcceptanceResult.Accepted(
                            true, target.requesterAccountId());
                } else if ("PENDING".equals(target.state())) {
                    UUID conversationId = establishRelationship(connection, target);
                    ensureFriendshipMapping(connection, conversationId);
                    markAccepted(connection, target.requestId());
                    result = new LegacyV1FriendRequestAcceptanceResult.Accepted(
                            false, target.requesterAccountId());
                } else {
                    result = LegacyV1FriendRequestAcceptanceResult.Rejected.INSTANCE;
                }
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException(
                    "V1 friend request acceptance failed", exception);
        }
    }

    private static Target lockTarget(Connection connection, long legacyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT request.id, request.requester_account_id,
                       request.recipient_account_id, request.state
                FROM chat.legacy_v1_contact_request_map mapping
                JOIN chat.contact_request request ON request.id = mapping.contact_request_id
                JOIN chat.account requester ON requester.id = request.requester_account_id
                JOIN chat.account recipient ON recipient.id = request.recipient_account_id
                WHERE mapping.legacy_request_id = ?
                  AND requester.disabled_at IS NULL AND recipient.disabled_at IS NULL
                FOR UPDATE OF request
                """)) {
            statement.setLong(1, legacyId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Target result = new Target(
                        row.getObject("id", UUID.class),
                        row.getObject("requester_account_id", UUID.class),
                        row.getObject("recipient_account_id", UUID.class),
                        row.getString("state"));
                if (row.next()) throw new SQLException("V1 request mapping returned duplicates");
                return result;
            }
        }
    }

    private static UUID establishRelationship(Connection connection, Target target)
            throws SQLException {
        UUID first = min(target.requesterAccountId(), target.recipientAccountId());
        UUID second = first.equals(target.requesterAccountId())
                ? target.recipientAccountId() : target.requesterAccountId();
        UUID conversationId = findDirectConversation(connection, first, second);
        if (conversationId == null) {
            conversationId = UUID.randomUUID();
            insertConversation(connection, conversationId, first, second);
        } else {
            reactivateMembers(connection, conversationId, first, second);
        }
        requireExactMembers(connection, conversationId, first, second);
        return conversationId;
    }

    private static UUID findDirectConversation(
            Connection connection, UUID first, UUID second) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT direct.conversation_id
                FROM chat.direct_conversation direct
                JOIN chat.conversation conversation
                  ON conversation.id = direct.conversation_id
                 AND conversation.kind = 'DIRECT'
                WHERE direct.first_account_id = ? AND direct.second_account_id = ?
                FOR UPDATE OF direct, conversation
                """)) {
            statement.setObject(1, first);
            statement.setObject(2, second);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                UUID result = row.getObject(1, UUID.class);
                if (row.next()) throw new SQLException("direct pair returned duplicates");
                return result;
            }
        }
    }

    private static void insertConversation(
            Connection connection, UUID conversationId, UUID first, UUID second)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat.conversation(id, kind) VALUES (?, 'DIRECT')")) {
            statement.setObject(1, conversationId);
            requireOne(statement.executeUpdate(), "conversation insert");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.direct_conversation(
                    conversation_id, first_account_id, second_account_id)
                VALUES (?, ?, ?)
                """)) {
            statement.setObject(1, conversationId);
            statement.setObject(2, first);
            statement.setObject(3, second);
            requireOne(statement.executeUpdate(), "direct pair insert");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.conversation_member(conversation_id, account_id)
                VALUES (?, ?), (?, ?)
                """)) {
            statement.setObject(1, conversationId);
            statement.setObject(2, first);
            statement.setObject(3, conversationId);
            statement.setObject(4, second);
            if (statement.executeUpdate() != 2) {
                throw new SQLException("direct membership insert affected unexpected rows");
            }
        }
    }

    private static void reactivateMembers(
            Connection connection, UUID conversationId, UUID first, UUID second)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation_member
                SET left_at = NULL,
                    joined_at = CASE WHEN left_at IS NULL THEN joined_at
                                     ELSE transaction_timestamp() END
                WHERE conversation_id = ? AND account_id IN (?, ?)
                """)) {
            statement.setObject(1, conversationId);
            statement.setObject(2, first);
            statement.setObject(3, second);
            if (statement.executeUpdate() != 2) {
                throw new SQLException("direct membership reactivation was incomplete");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chat.conversation SET updated_at = transaction_timestamp() WHERE id = ?")) {
            statement.setObject(1, conversationId);
            requireOne(statement.executeUpdate(), "conversation touch");
        }
    }

    private static void ensureFriendshipMapping(Connection connection, UUID conversationId)
            throws SQLException {
        if (hasValidFriendshipMapping(connection, conversationId)) return;
        long legacyId = nextUnusedFriendshipId(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.legacy_v1_conversation_map(
                    legacy_kind, legacy_conversation_id, conversation_id)
                VALUES ('FRIENDSHIP', ?, ?)
                """)) {
            statement.setLong(1, legacyId);
            statement.setObject(2, conversationId);
            requireOne(statement.executeUpdate(), "friendship mapping insert");
        }
    }

    private static long nextUnusedFriendshipId(Connection connection) throws SQLException {
        while (true) {
            long candidate;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT nextval('chat.legacy_v1_friendship_id_seq')");
                    ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("friendship ID allocation returned no row");
                candidate = row.getLong(1);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT EXISTS (
                        SELECT 1 FROM chat.legacy_v1_conversation_map
                        WHERE legacy_kind = 'FRIENDSHIP' AND legacy_conversation_id = ?)
                    """)) {
                statement.setLong(1, candidate);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        throw new SQLException("friendship ID occupancy returned no row");
                    }
                    if (!row.getBoolean(1)) return candidate;
                }
            }
        }
    }

    private static void requireCompleteRelationship(Connection connection, Target target)
            throws SQLException {
        UUID first = min(target.requesterAccountId(), target.recipientAccountId());
        UUID second = first.equals(target.requesterAccountId())
                ? target.recipientAccountId() : target.requesterAccountId();
        UUID conversationId = findDirectConversation(connection, first, second);
        if (conversationId == null) {
            throw new SQLException("accepted request has no direct conversation");
        }
        requireExactMembers(connection, conversationId, first, second);
        if (!hasValidFriendshipMapping(connection, conversationId)) {
            throw new SQLException("accepted request has no V1 friendship mapping");
        }
    }

    private static void requireExactMembers(
            Connection connection, UUID conversationId, UUID first, UUID second)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE account_id IN (?, ?) AND left_at IS NULL) AS active
                FROM chat.conversation_member WHERE conversation_id = ?
                """)) {
            statement.setObject(1, first);
            statement.setObject(2, second);
            statement.setObject(3, conversationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || row.getInt("total") != 2 || row.getInt("active") != 2) {
                    throw new SQLException("direct conversation membership is inconsistent");
                }
            }
        }
    }

    private static boolean hasValidFriendshipMapping(
            Connection connection, UUID conversationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT legacy_kind, legacy_conversation_id
                FROM chat.legacy_v1_conversation_map WHERE conversation_id = ?
                """)) {
            statement.setObject(1, conversationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return false;
                boolean valid = "FRIENDSHIP".equals(row.getString(1))
                        && row.getLong(2) > 0 && row.getLong(2) <= Integer.MAX_VALUE;
                if (row.next()) throw new SQLException("conversation mapping returned duplicates");
                if (!valid) throw new SQLException("direct conversation has invalid V1 mapping");
                return true;
            }
        }
    }

    private static void markAccepted(Connection connection, UUID requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.contact_request
                SET state = 'ACCEPTED', resolved_at = transaction_timestamp()
                WHERE id = ? AND state = 'PENDING'
                """)) {
            statement.setObject(1, requestId);
            requireOne(statement.executeUpdate(), "contact request acceptance");
        }
    }

    private static UUID min(UUID left, UUID right) {
        // PostgreSQL UUID ordering is unsigned byte/hex order; UUID.compareTo uses
        // signed longs and differs for identifiers with the high bit set.
        return left.toString().compareTo(right.toString()) <= 0 ? left : right;
    }

    private static void requireOne(int count, String operation) throws SQLException {
        if (count != 1) throw new SQLException(operation + " affected unexpected rows");
    }

    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }

    private record Target(
            UUID requestId,
            UUID requesterAccountId,
            UUID recipientAccountId,
            String state) { }
}
