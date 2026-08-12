package com.fallingnight.chat.persistence.postgres;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomAudiencePort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
/** Batch authorization filter for currently connected V1 room recipients. */
public final class PostgresLegacyV1RoomAudienceAdapter implements LegacyV1RoomAudiencePort {
    private final DataSource dataSource;
    public PostgresLegacyV1RoomAudienceAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }
    @Override public Set<UUID> activeMappedMembers(UUID conversationId, Set<UUID> candidates) {
        if (candidates.isEmpty()) return Set.of();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                    SELECT member.account_id
                    FROM chat.conversation_member member
                    JOIN chat.conversation conversation ON conversation.id = member.conversation_id
                     AND conversation.kind = 'GROUP'
                    JOIN chat.legacy_v1_conversation_map room
                      ON room.conversation_id = conversation.id AND room.legacy_kind = 'ROOM'
                    JOIN chat.account account ON account.id = member.account_id
                     AND account.disabled_at IS NULL
                    JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                    WHERE member.conversation_id = ? AND member.left_at IS NULL
                      AND member.account_id = ANY (?)
                    """)) {
            statement.setObject(1, conversationId);
            statement.setArray(2, connection.createArrayOf("uuid", candidates.toArray()));
            Set<UUID> result = new HashSet<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(row.getObject(1, UUID.class));
            }
            return Set.copyOf(result);
        } catch (SQLException exception) {
            throw new ConversationPersistenceException("V1 room audience read failed", exception);
        }
    }
}
