package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomRenameCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomRenamePort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomRenameResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Serializable administrator-authorized canonical V1 room rename. */
public final class PostgresLegacyV1RoomRenameAdapter implements LegacyV1RoomRenamePort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1RoomRenameAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RoomRenameResult rename(LegacyV1RoomRenameCommand command) {
        Objects.requireNonNull(command, "command");
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(command); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException("V1 room rename failed", last);
    }

    private LegacyV1RoomRenameResult attempt(LegacyV1RoomRenameCommand command)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                Target target = lockAuthorizedRoom(connection, command);
                if (target == null) {
                    connection.commit();
                    return LegacyV1RoomRenameResult.Rejected.ROOM_ADMIN_REQUIRED;
                }
                if (target.oldName().equals(command.newName())) {
                    connection.commit();
                    return result(target, command.newName(), false, target.updatedAt());
                }
                Instant updatedAt;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE chat.conversation
                        SET title = ?, updated_at = transaction_timestamp()
                        WHERE id = ? AND kind = 'GROUP' AND title = ?
                        RETURNING updated_at
                        """)) {
                    statement.setString(1, command.newName());
                    statement.setObject(2, target.conversationId());
                    statement.setString(3, target.oldName());
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new SQLException("V1 room title changed while locked");
                        updatedAt = row.getObject(1, OffsetDateTime.class).toInstant();
                        if (row.next()) throw new SQLException("V1 room rename updated multiple rows");
                    }
                }
                connection.commit();
                return result(target, command.newName(), true, updatedAt);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        }
    }

    private static Target lockAuthorizedRoom(Connection connection,
            LegacyV1RoomRenameCommand command) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation.id, room.legacy_conversation_id,
                       conversation.title, conversation.updated_at
                FROM chat.account actor
                JOIN chat.legacy_v1_account_map actor_map ON actor_map.account_id = actor.id
                JOIN chat.legacy_v1_conversation_map room
                  ON room.legacy_kind = 'ROOM' AND room.legacy_conversation_id = ?
                JOIN chat.conversation conversation
                  ON conversation.id = room.conversation_id AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = conversation.id AND lifecycle.closed_at IS NULL
                JOIN chat.conversation_member member
                  ON member.conversation_id = conversation.id
                 AND member.account_id = actor.id AND member.left_at IS NULL
                 AND member.role IN ('OWNER', 'ADMIN')
                WHERE actor.id = ? AND actor.disabled_at IS NULL
                FOR UPDATE OF conversation
                """)) {
            statement.setLong(1, command.legacyRoomId());
            statement.setObject(2, command.actorAccountId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Target result = new Target(row.getObject("id", UUID.class),
                        row.getLong("legacy_conversation_id"), row.getString("title"),
                        row.getObject("updated_at", OffsetDateTime.class).toInstant());
                if (row.next()) throw new SQLException("V1 room rename target duplicated");
                return result;
            }
        }
    }

    private static LegacyV1RoomRenameResult.Renamed result(Target target,
            String newName, boolean changed, Instant updatedAt) {
        return new LegacyV1RoomRenameResult.Renamed(target.conversationId(),
                target.legacyRoomId(), target.oldName(), newName, changed, updatedAt);
    }

    private static boolean retryable(SQLException exception) {
        for (SQLException current = exception; current != null;
                current = current.getNextException()) {
            if ("40001".equals(current.getSQLState()) || "40P01".equals(current.getSQLState()))
                return true;
        }
        return false;
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }

    private record Target(UUID conversationId, long legacyRoomId,
            String oldName, Instant updatedAt) { }
}
