package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFile;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFiles;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFilesPort;
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

/** Exact administrator-only V1 room active-file projection from PostgreSQL. */
public final class PostgresLegacyV1RoomFilesAdapter implements LegacyV1RoomFilesPort {
    private final DataSource dataSource;

    public PostgresLegacyV1RoomFilesAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public QueryResult read(UUID actor, long roomId) {
        Objects.requireNonNull(actor, "actor");
        if (roomId <= 0 || roomId > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid V1 room files query");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                QueryResult result = query(connection, actor, roomId);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                try {
                    connection.rollback();
                } catch (SQLException failure) {
                    exception.addSuppressed(failure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException("V1 room files read failed", exception);
        }
    }

    private static QueryResult query(Connection connection, UUID actor, long roomId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT resource.total_file_space,
                       listed.legacy_file_id, listed.file_name,
                       listed.byte_size, listed.created_at
                FROM chat.account actor
                JOIN chat.legacy_v1_account_map actor_map
                  ON actor_map.account_id = actor.id
                JOIN chat.legacy_v1_conversation_map room
                  ON room.legacy_kind = 'ROOM' AND room.legacy_conversation_id = ?
                JOIN chat.conversation conversation
                  ON conversation.id = room.conversation_id
                 AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = conversation.id
                 AND lifecycle.closed_at IS NULL
                JOIN chat.conversation_member membership
                  ON membership.conversation_id = conversation.id
                 AND membership.account_id = actor.id
                 AND membership.left_at IS NULL
                 AND membership.role IN ('OWNER', 'ADMIN')
                JOIN chat.group_resource_policy resource
                  ON resource.conversation_id = conversation.id
                LEFT JOIN LATERAL (
                    SELECT file_map.legacy_file_id, attachment.file_name,
                           attachment.byte_size, attachment.created_at
                    FROM chat.legacy_v1_attachment_map file_map
                    JOIN chat.attachment attachment
                      ON attachment.id = file_map.attachment_id
                     AND attachment.conversation_id = file_map.conversation_id
                     AND attachment.state = 'READY'
                    JOIN chat.message message
                      ON message.attachment_id = attachment.id
                     AND message.conversation_id = attachment.conversation_id
                     AND message.message_type = 2
                    JOIN chat.legacy_v1_message_map message_map
                      ON message_map.message_id = message.id
                     AND message_map.legacy_kind = 'ROOM'
                     AND message_map.legacy_conversation_id = ?
                     AND message_map.legacy_content_type IN ('file', 'image', 'video')
                    WHERE file_map.legacy_kind = 'ROOM'
                      AND file_map.legacy_conversation_id = ?
                      AND file_map.conversation_id = conversation.id
                    ORDER BY attachment.created_at DESC, file_map.legacy_file_id DESC
                    LIMIT ?
                ) listed ON TRUE
                WHERE actor.id = ? AND actor.disabled_at IS NULL
                ORDER BY listed.created_at DESC NULLS LAST,
                         listed.legacy_file_id DESC NULLS LAST
                """)) {
            statement.setLong(1, roomId);
            statement.setLong(2, roomId);
            statement.setLong(3, roomId);
            statement.setInt(4, LegacyV1RoomFiles.MAX_FILES + 1);
            statement.setObject(5, actor);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return QueryResult.Rejected.ROOM_ADMIN_REQUIRED;
                long maxFileSpace = rows.getLong("total_file_space");
                List<LegacyV1RoomFile> files = new ArrayList<>();
                long used = 0;
                do {
                    long fileId = rows.getLong("legacy_file_id");
                    if (rows.wasNull()) continue;
                    LegacyV1RoomFile file = new LegacyV1RoomFile(
                            fileId, rows.getString("file_name"), rows.getLong("byte_size"),
                            rows.getObject("created_at", OffsetDateTime.class).toInstant());
                    files.add(file);
                    used = Math.addExact(used, file.byteSize());
                } while (rows.next());
                try {
                    return new QueryResult.Authorized(
                            new LegacyV1RoomFiles(files, used, maxFileSpace));
                } catch (IllegalArgumentException | ArithmeticException exception) {
                    throw new SQLException("V1 room files projection is invalid", exception);
                }
            }
        }
    }
}
