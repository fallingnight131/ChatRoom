package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.messaging.ConversationEventOutboxStatus;
import com.fallingnight.chat.application.messaging.ConversationEventOutboxStatusPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** One aggregate, identity-free PostgreSQL outbox health query. */
public final class PostgresConversationEventOutboxStatusAdapter
        implements ConversationEventOutboxStatusPort {
    private final DataSource dataSource;

    public PostgresConversationEventOutboxStatusAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public ConversationEventOutboxStatus readStatus(Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        String sql = """
                WITH unpublished AS (
                    SELECT * FROM chat.conversation_event_outbox
                    WHERE published_at IS NULL
                ), ready AS (
                    SELECT event.event_id FROM unpublished event
                    WHERE event.available_at <= ?
                      AND (event.claim_owner IS NULL OR event.claim_expires_at <= ?)
                      AND NOT EXISTS (
                          SELECT 1 FROM unpublished earlier
                          WHERE earlier.conversation_id = event.conversation_id
                            AND earlier.conversation_sequence < event.conversation_sequence)
                )
                SELECT count(*), (SELECT count(*) FROM ready),
                       count(*) FILTER (WHERE claim_owner IS NOT NULL
                           AND claim_expires_at > ?),
                       count(*) FILTER (WHERE available_at > ?),
                       count(*) FILTER (WHERE attempt_count > 1),
                       COALESCE(max(attempt_count), 0), min(created_at)
                FROM unpublished
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            OffsetDateTime at = OffsetDateTime.ofInstant(observedAt, ZoneOffset.UTC);
            statement.setObject(1, at);
            statement.setObject(2, at);
            statement.setObject(3, at);
            statement.setObject(4, at);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("conversation event outbox status missing");
                }
                OffsetDateTime oldest = result.getObject(7, OffsetDateTime.class);
                return new ConversationEventOutboxStatus(
                        result.getLong(1), result.getLong(2), result.getLong(3),
                        result.getLong(4), result.getLong(5), result.getInt(6),
                        Optional.ofNullable(oldest).map(OffsetDateTime::toInstant));
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException(
                    "conversation event outbox status failed", exception);
        }
    }
}
