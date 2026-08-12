package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1MessageIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1MessageProjectionPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Exact PostgreSQL projection for retained V1/V2 message identity translation. */
public final class PostgresLegacyV1MessageProjection implements LegacyV1MessageProjectionPort {
    private static final String BY_LEGACY_ID = """
            SELECT legacy_kind, legacy_conversation_id, legacy_message_id,
                   conversation_id, message_id
            FROM chat.legacy_v1_message_map
            WHERE legacy_kind = ? AND legacy_message_id = ?
            """;
    private static final String BY_MESSAGE_ID = """
            SELECT legacy_kind, legacy_conversation_id, legacy_message_id,
                   conversation_id, message_id
            FROM chat.legacy_v1_message_map
            WHERE message_id = ?
            """;

    private final DataSource dataSource;

    public PostgresLegacyV1MessageProjection(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<LegacyV1MessageIdentity> findByLegacyId(
            LegacyV1ConversationKind kind, long legacyMessageId) {
        Objects.requireNonNull(kind, "kind");
        if (legacyMessageId <= 0) {
            return Optional.empty();
        }
        return find(BY_LEGACY_ID, statement -> {
            statement.setString(1, kind.name());
            statement.setLong(2, legacyMessageId);
        });
    }

    @Override
    public Optional<LegacyV1MessageIdentity> findByMessageId(UUID messageId) {
        Objects.requireNonNull(messageId, "messageId");
        return find(BY_MESSAGE_ID, statement -> statement.setObject(1, messageId));
    }

    private Optional<LegacyV1MessageIdentity> find(String sql, StatementBinder binder) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                LegacyV1MessageIdentity identity = new LegacyV1MessageIdentity(
                        LegacyV1ConversationKind.valueOf(result.getString("legacy_kind")),
                        result.getLong("legacy_conversation_id"),
                        result.getLong("legacy_message_id"),
                        result.getObject("conversation_id", UUID.class),
                        result.getObject("message_id", UUID.class));
                if (result.next()) {
                    throw new SQLException("V1 message projection returned multiple rows");
                }
                return Optional.of(identity);
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new MessagePersistenceException("V1 message projection failed", exception);
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
