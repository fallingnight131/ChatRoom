package com.fallingnight.chat.persistence.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Transaction-local bilateral block policy for new direct-contact writes. */
final class PostgresAccountBlockPolicy {
    private PostgresAccountBlockPolicy() { }

    static boolean allowsConversationWrite(
            Connection connection, UUID conversationId, UUID actorAccountId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation.kind, direct.first_account_id,
                       direct.second_account_id
                FROM chat.conversation conversation
                LEFT JOIN chat.direct_conversation direct
                  ON direct.conversation_id = conversation.id
                WHERE conversation.id = ?
                """)) {
            statement.setObject(1, conversationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return false;
                if (!"DIRECT".equals(row.getString("kind"))) return true;
                UUID first = row.getObject("first_account_id", UUID.class);
                UUID second = row.getObject("second_account_id", UUID.class);
                if (first == null || second == null) return false;
                UUID peer;
                if (actorAccountId.equals(first)) peer = second;
                else if (actorAccountId.equals(second)) peer = first;
                else return false;
                return lockEnabledPairAndAllows(connection, actorAccountId, peer);
            }
        }
    }

    static boolean lockEnabledPairAndAllows(
            Connection connection, UUID leftAccountId, UUID rightAccountId)
            throws SQLException {
        Set<UUID> enabled = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM chat.account
                WHERE id IN (?, ?) AND disabled_at IS NULL
                ORDER BY id FOR SHARE
                """)) {
            statement.setObject(1, leftAccountId);
            statement.setObject(2, rightAccountId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) enabled.add(row.getObject(1, UUID.class));
            }
        }
        if (!enabled.contains(leftAccountId) || !enabled.contains(rightAccountId)) return false;
        if (leftAccountId.equals(rightAccountId)) return true;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1 FROM chat.account_block
                    WHERE (blocker_account_id = ? AND blocked_account_id = ?)
                       OR (blocker_account_id = ? AND blocked_account_id = ?))
                """)) {
            statement.setObject(1, leftAccountId);
            statement.setObject(2, rightAccountId);
            statement.setObject(3, rightAccountId);
            statement.setObject(4, leftAccountId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("account block check returned no row");
                return !row.getBoolean(1);
            }
        }
    }
}
