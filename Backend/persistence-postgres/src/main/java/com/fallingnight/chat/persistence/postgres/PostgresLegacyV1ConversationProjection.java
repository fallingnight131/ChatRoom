package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationProjectionPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** PostgreSQL projection for temporary V1 conversation identity translation. */
public final class PostgresLegacyV1ConversationProjection
        implements LegacyV1ConversationProjectionPort {
    private static final String BY_LEGACY_ID = """
            SELECT legacy_kind, legacy_conversation_id, conversation_id
            FROM chat.legacy_v1_conversation_map
            WHERE legacy_kind = ? AND legacy_conversation_id = ?
            """;
    private static final String BY_CONVERSATION_ID = """
            SELECT legacy_kind, legacy_conversation_id, conversation_id
            FROM chat.legacy_v1_conversation_map
            WHERE conversation_id = ?
            """;

    private final DataSource dataSource;

    public PostgresLegacyV1ConversationProjection(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<LegacyV1ConversationIdentity> findByLegacyId(
            LegacyV1ConversationKind kind, long legacyConversationId) {
        Objects.requireNonNull(kind, "kind");
        if (legacyConversationId <= 0) {
            return Optional.empty();
        }
        return find(BY_LEGACY_ID, statement -> {
            statement.setString(1, kind.name());
            statement.setLong(2, legacyConversationId);
        });
    }

    @Override
    public Optional<LegacyV1ConversationIdentity> findByConversationId(UUID conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        return find(BY_CONVERSATION_ID, statement -> statement.setObject(1, conversationId));
    }

    private Optional<LegacyV1ConversationIdentity> find(
            String sql, StatementBinder binder) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                LegacyV1ConversationIdentity identity = new LegacyV1ConversationIdentity(
                        LegacyV1ConversationKind.valueOf(result.getString("legacy_kind")),
                        result.getLong("legacy_conversation_id"),
                        result.getObject("conversation_id", UUID.class));
                if (result.next()) {
                    throw new SQLException("V1 conversation projection returned multiple rows");
                }
                return Optional.of(identity);
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new ConversationPersistenceException(
                    "V1 conversation projection failed", exception);
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
