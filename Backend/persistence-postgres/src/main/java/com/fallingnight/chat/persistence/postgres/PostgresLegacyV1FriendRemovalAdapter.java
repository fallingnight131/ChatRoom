package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRemovalPort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRemovalResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Atomic membership termination for a canonical V1-compatible friendship. */
public final class PostgresLegacyV1FriendRemovalAdapter
        implements LegacyV1FriendRemovalPort {
    private final DataSource dataSource;

    public PostgresLegacyV1FriendRemovalAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public LegacyV1FriendRemovalResult remove(
            UUID actorAccountId, String targetUsername) {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(targetUsername, "targetUsername");
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                UUID targetAccountId = findTarget(connection, targetUsername);
                LegacyV1FriendRemovalResult result;
                if (targetAccountId == null) {
                    result = LegacyV1FriendRemovalResult.Rejected.TARGET_NOT_FOUND;
                } else if (targetAccountId.equals(actorAccountId)) {
                    result = LegacyV1FriendRemovalResult.Rejected.SELF_REMOVAL;
                } else {
                    lockAndValidateParticipants(
                            connection, actorAccountId, targetAccountId, targetUsername);
                    result = removeRelationship(
                            connection, actorAccountId, targetAccountId, targetUsername);
                }
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException("V1 friend removal failed", exception);
        }
    }

    private static UUID findTarget(Connection connection, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account.id
                FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE account.username_key = ? AND account.disabled_at IS NULL
                """)) {
            statement.setString(1, username);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                UUID result = row.getObject(1, UUID.class);
                if (row.next()) throw new SQLException("target username returned duplicates");
                return result;
            }
        }
    }

    private static void lockAndValidateParticipants(
            Connection connection, UUID actor, UUID target, String targetUsername)
            throws SQLException {
        List<Participant> participants = new ArrayList<>(2);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account.id, account.username_key
                FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE account.id IN (?, ?) AND account.disabled_at IS NULL
                ORDER BY account.id
                FOR SHARE OF account
                """)) {
            statement.setObject(1, actor);
            statement.setObject(2, target);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    participants.add(new Participant(
                            row.getObject("id", UUID.class), row.getString("username_key")));
                }
            }
        }
        boolean actorPresent = participants.stream()
                .anyMatch(participant -> participant.accountId().equals(actor));
        Participant lockedTarget = participants.stream()
                .filter(participant -> participant.accountId().equals(target))
                .findFirst().orElse(null);
        if (!actorPresent) throw new SQLException("authenticated V1 actor is unavailable");
        if (lockedTarget == null || !lockedTarget.username().equals(targetUsername)) {
            throw new SQLException("V1 friend removal target changed during resolution");
        }
        if (participants.size() != 2) {
            throw new SQLException("V1 friend removal participants are inconsistent");
        }
    }

    private static LegacyV1FriendRemovalResult removeRelationship(
            Connection connection, UUID actor, UUID target, String targetUsername)
            throws SQLException {
        UUID first = min(actor, target);
        UUID second = first.equals(actor) ? target : actor;
        UUID conversationId = lockDirectConversation(connection, first, second);
        if (conversationId == null) {
            return LegacyV1FriendRemovalResult.Rejected.NOT_FRIENDS;
        }
        requireFriendshipMapping(connection, conversationId);
        List<Member> members = lockMembers(connection, conversationId);
        if (members.size() != 2
                || members.stream().noneMatch(member -> member.accountId().equals(first))
                || members.stream().noneMatch(member -> member.accountId().equals(second))) {
            throw new SQLException("direct conversation membership is inconsistent");
        }
        long active = members.stream().filter(member -> member.leftAt() == null).count();
        if (active == 0) {
            return new LegacyV1FriendRemovalResult.Removed(true, target, targetUsername);
        }
        if (active != 2) {
            throw new SQLException("direct conversation membership is partially active");
        }
        terminateMembers(connection, conversationId);
        touchConversation(connection, conversationId);
        return new LegacyV1FriendRemovalResult.Removed(false, target, targetUsername);
    }

    private static UUID lockDirectConversation(
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

    private static void requireFriendshipMapping(Connection connection, UUID conversationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT legacy_kind, legacy_conversation_id
                FROM chat.legacy_v1_conversation_map
                WHERE conversation_id = ?
                """)) {
            statement.setObject(1, conversationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()
                        || !"FRIENDSHIP".equals(row.getString("legacy_kind"))
                        || row.getLong("legacy_conversation_id") <= 0
                        || row.getLong("legacy_conversation_id") > Integer.MAX_VALUE) {
                    throw new SQLException("direct conversation has no valid V1 mapping");
                }
                if (row.next()) throw new SQLException("conversation mapping returned duplicates");
            }
        }
    }

    private static List<Member> lockMembers(Connection connection, UUID conversationId)
            throws SQLException {
        List<Member> members = new ArrayList<>(2);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account_id, left_at
                FROM chat.conversation_member
                WHERE conversation_id = ?
                ORDER BY account_id
                FOR UPDATE
                """)) {
            statement.setObject(1, conversationId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    members.add(new Member(
                            row.getObject("account_id", UUID.class), row.getTimestamp("left_at")));
                }
            }
        }
        return members;
    }

    private static void terminateMembers(Connection connection, UUID conversationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation_member
                SET left_at = transaction_timestamp()
                WHERE conversation_id = ? AND left_at IS NULL
                """)) {
            statement.setObject(1, conversationId);
            if (statement.executeUpdate() != 2) {
                throw new SQLException("friend removal did not terminate both memberships");
            }
        }
    }

    private static void touchConversation(Connection connection, UUID conversationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chat.conversation SET updated_at = transaction_timestamp() WHERE id = ?")) {
            statement.setObject(1, conversationId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("conversation touch affected unexpected rows");
            }
        }
    }

    private static UUID min(UUID left, UUID right) {
        return left.toString().compareTo(right.toString()) <= 0 ? left : right;
    }

    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }

    private record Participant(UUID accountId, String username) { }
    private record Member(UUID accountId, Timestamp leftAt) { }
}
