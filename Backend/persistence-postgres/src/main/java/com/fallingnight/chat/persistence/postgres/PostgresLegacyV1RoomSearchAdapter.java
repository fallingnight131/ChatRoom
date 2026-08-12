package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomSearchEntry;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomSearchPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Enabled-actor, completely mapped, deterministic V1 GROUP search. */
public final class PostgresLegacyV1RoomSearchAdapter implements LegacyV1RoomSearchPort {
    private final DataSource dataSource;
    public PostgresLegacyV1RoomSearchAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public List<LegacyV1RoomSearchEntry> search(
            UUID actorAccountId, String keyword, int limit) {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(keyword, "keyword");
        if (keyword.isBlank() || limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("invalid V1 room search request");
        }
        Long exactId = positiveV1Id(keyword);
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try {
                requireActor(connection, actorAccountId);
                List<LegacyV1RoomSearchEntry> result = read(
                        connection, exactId, keyword, limit);
                connection.commit(); return List.copyOf(result);
            } catch (RuntimeException | SQLException exception) {
                try { connection.rollback(); } catch (SQLException rollback) {
                    exception.addSuppressed(rollback);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException("V1 room search failed", exception);
        }
    }

    private static void requireActor(Connection connection, UUID actorAccountId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*)
                FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE account.id = ? AND account.disabled_at IS NULL
                """)) {
            statement.setObject(1, actorAccountId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || row.getInt(1) != 1) {
                    throw new SQLException("V1 room search actor is not eligible");
                }
            }
        }
    }

    private static List<LegacyV1RoomSearchEntry> read(Connection connection,
            Long exactId, String keyword, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH candidates AS (
                    SELECT conversation.id, room_map.legacy_conversation_id,
                           conversation.title,
                           (SELECT count(*) FROM chat.conversation_member active_member
                            WHERE active_member.conversation_id = conversation.id
                              AND active_member.left_at IS NULL) AS member_count
                    FROM chat.conversation conversation
                    JOIN chat.legacy_v1_conversation_map room_map
                      ON room_map.conversation_id = conversation.id
                     AND room_map.legacy_kind = 'ROOM'
                    WHERE conversation.kind = 'GROUP'
                      AND ((? IS NOT NULL AND room_map.legacy_conversation_id = ?)
                           OR (? IS NULL AND conversation.title ILIKE ? ESCAPE '\\'))
                    ORDER BY room_map.legacy_conversation_id, conversation.id
                    LIMIT ?
                )
                SELECT candidates.id, candidates.legacy_conversation_id,
                       candidates.title, creator_map.legacy_user_id,
                       candidates.member_count
                FROM candidates
                LEFT JOIN chat.conversation_member owner
                  ON owner.conversation_id = candidates.id
                 AND owner.role = 'OWNER' AND owner.left_at IS NULL
                LEFT JOIN chat.account creator
                  ON creator.id = owner.account_id AND creator.disabled_at IS NULL
                LEFT JOIN chat.legacy_v1_account_map creator_map
                  ON creator_map.account_id = creator.id
                ORDER BY candidates.legacy_conversation_id, candidates.id
                """)) {
            if (exactId == null) statement.setNull(1, java.sql.Types.BIGINT);
            else statement.setLong(1, exactId);
            if (exactId == null) statement.setNull(2, java.sql.Types.BIGINT);
            else statement.setLong(2, exactId);
            if (exactId == null) statement.setNull(3, java.sql.Types.BIGINT);
            else statement.setLong(3, exactId);
            statement.setString(4, "%" + escapeLike(keyword) + "%");
            statement.setInt(5, limit);
            try (ResultSet row = statement.executeQuery()) {
                List<LegacyV1RoomSearchEntry> result = new ArrayList<>();
                while (row.next()) {
                    Long creatorId = row.getObject("legacy_user_id", Long.class);
                    if (creatorId == null) {
                        throw new SQLException("V1 room search creator mapping is incomplete");
                    }
                    result.add(new LegacyV1RoomSearchEntry(
                            row.getObject("id", UUID.class),
                            row.getLong("legacy_conversation_id"), row.getString("title"),
                            creatorId, row.getInt("member_count")));
                }
                return result;
            }
        }
    }

    private static Long positiveV1Id(String keyword) {
        if (!keyword.matches("\\+?[0-9]+")) return null;
        try {
            long value = Long.parseLong(keyword.startsWith("+")
                    ? keyword.substring(1) : keyword);
            return value > 0 && value <= Integer.MAX_VALUE ? value : null;
        } catch (NumberFormatException exception) { return null; }
    }

    private static String escapeLike(String literal) {
        return literal.replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_");
    }
}
