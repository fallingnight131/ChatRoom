package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendDirectoryPort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendDirectoryState;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendState;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Complete bounded PostgreSQL projection for the detached V1 friend list. */
public final class PostgresLegacyV1FriendDirectoryAdapter
        implements LegacyV1FriendDirectoryPort {
    private static final String FRIENDS = """
            SELECT c.id AS conversation_id,
                   peer.id AS peer_account_id,
                   peer.username_key,
                   peer.display_name,
                   (SELECT count(*)
                    FROM chat.message message
                    WHERE message.conversation_id = c.id
                      AND message.conversation_sequence > member.last_read_sequence) AS unread,
                   COALESCE((
                       SELECT max(mapping.legacy_message_id)
                       FROM chat.message message
                       JOIN chat.legacy_v1_message_map mapping
                         ON mapping.message_id = message.id
                        AND mapping.conversation_id = message.conversation_id
                        AND mapping.legacy_kind = 'FRIENDSHIP'
                       WHERE message.conversation_id = c.id
                         AND message.conversation_sequence <= peer_member.last_read_sequence
                   ), 0) AS peer_last_read_message_id
            FROM chat.conversation_member member
            JOIN chat.conversation c
              ON c.id = member.conversation_id AND c.kind = 'DIRECT'
            JOIN chat.account owner
              ON owner.id = member.account_id AND owner.disabled_at IS NULL
            JOIN chat.direct_conversation direct
              ON direct.conversation_id = c.id
            JOIN chat.account peer
              ON peer.id = CASE
                   WHEN direct.first_account_id = member.account_id
                   THEN direct.second_account_id ELSE direct.first_account_id END
             AND peer.disabled_at IS NULL
            JOIN chat.conversation_member peer_member
              ON peer_member.conversation_id = c.id
             AND peer_member.account_id = peer.id
             AND peer_member.left_at IS NULL
            WHERE member.account_id = ? AND member.left_at IS NULL
            ORDER BY peer.display_name, peer.username_key, c.id
            LIMIT ?
            """;

    private final DataSource dataSource;

    public PostgresLegacyV1FriendDirectoryAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public LegacyV1FriendDirectoryState read(UUID accountId, int maximumFriends) {
        Objects.requireNonNull(accountId, "accountId");
        if (maximumFriends < 1 || maximumFriends > 10_000) {
            throw new IllegalArgumentException("maximumFriends");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                List<LegacyV1FriendState> friends = readFriends(
                        connection, accountId, maximumFriends);
                int pending = readPending(connection, accountId);
                connection.commit();
                return new LegacyV1FriendDirectoryState(friends, pending);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException("V1 friend directory read failed", exception);
        }
    }

    private static List<LegacyV1FriendState> readFriends(
            Connection connection, UUID accountId, int maximumFriends) throws SQLException {
        List<LegacyV1FriendState> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(FRIENDS)) {
            statement.setObject(1, accountId);
            statement.setInt(2, maximumFriends + 1);
            statement.setFetchSize(maximumFriends + 1);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new LegacyV1FriendState(
                            rows.getObject("conversation_id", UUID.class),
                            rows.getObject("peer_account_id", UUID.class),
                            rows.getString("username_key"),
                            rows.getString("display_name"),
                            rows.getLong("unread"),
                            rows.getLong("peer_last_read_message_id")));
                }
            }
        }
        if (result.size() > maximumFriends) {
            throw new IllegalStateException("V1 friend directory exceeds its fixed bound");
        }
        return List.copyOf(result);
    }

    private static int readPending(Connection connection, UUID accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*) FROM chat.contact_request
                WHERE recipient_account_id = ? AND state = 'PENDING'
                """)) {
            statement.setObject(1, accountId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("pending request count is absent");
                return Math.toIntExact(row.getLong(1));
            }
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
