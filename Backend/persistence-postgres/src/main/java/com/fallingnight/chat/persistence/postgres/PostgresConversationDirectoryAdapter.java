package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.conversation.ConversationDirectoryCursor;
import com.fallingnight.chat.application.conversation.ConversationDirectoryPage;
import com.fallingnight.chat.application.conversation.ConversationDirectoryPort;
import com.fallingnight.chat.application.conversation.ConversationDirectoryQuery;
import com.fallingnight.chat.application.conversation.ConversationKind;
import com.fallingnight.chat.application.conversation.ConversationRole;
import com.fallingnight.chat.application.conversation.ConversationSummary;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Active-member conversation directory with a stable descending composite cursor. */
public final class PostgresConversationDirectoryAdapter implements ConversationDirectoryPort {
    private static final String SELECT = "SELECT c.id, c.kind, "
            + "CASE WHEN c.kind = 'DIRECT' THEN peer.display_name ELSE c.title END, "
            + "cm.role, c.next_sequence - 1, "
            + "LEAST(cm.last_read_sequence, c.next_sequence - 1), c.updated_at "
            + "FROM chat.conversation_member cm "
            + "JOIN chat.conversation c ON c.id = cm.conversation_id "
            + "JOIN chat.account owner ON owner.id = cm.account_id "
            + "LEFT JOIN chat.direct_conversation dc ON dc.conversation_id = c.id "
            + "LEFT JOIN chat.account peer ON peer.id = CASE "
            + "WHEN dc.first_account_id = cm.account_id THEN dc.second_account_id "
            + "ELSE dc.first_account_id END "
            + "WHERE cm.account_id = ? AND cm.left_at IS NULL AND owner.disabled_at IS NULL ";

    private final DataSource dataSource;

    public PostgresConversationDirectoryAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public ConversationDirectoryPage list(ConversationDirectoryQuery query) {
        Objects.requireNonNull(query, "query");
        boolean after = query.after().isPresent();
        String sql = SELECT
                + (after ? "AND (c.updated_at, c.id) < (?, ?) " : "")
                + "ORDER BY c.updated_at DESC, c.id DESC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setObject(parameter++, query.accountId());
            if (after) {
                ConversationDirectoryCursor cursor = query.after().orElseThrow();
                statement.setObject(parameter++, OffsetDateTime.ofInstant(
                        cursor.updatedAt(), java.time.ZoneOffset.UTC));
                statement.setObject(parameter++, cursor.conversationId());
            }
            statement.setInt(parameter, query.limit() + 1);
            statement.setFetchSize(query.limit() + 1);
            List<ConversationSummary> rows = new ArrayList<>(query.limit() + 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(read(result));
                }
            }
            boolean hasMore = rows.size() > query.limit();
            if (hasMore) {
                rows.removeLast();
            }
            Optional<ConversationDirectoryCursor> next = rows.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new ConversationDirectoryCursor(
                            rows.getLast().updatedAt(), rows.getLast().conversationId()));
            return new ConversationDirectoryPage(rows, next, hasMore);
        } catch (SQLException exception) {
            throw new ConversationPersistenceException(
                    "conversation directory read failed", exception);
        }
    }

    private static ConversationSummary read(ResultSet result) throws SQLException {
        return new ConversationSummary(
                result.getObject(1, UUID.class),
                ConversationKind.valueOf(result.getString(2)),
                result.getString(3),
                ConversationRole.valueOf(result.getString(4)),
                result.getLong(5),
                result.getLong(6),
                result.getObject(7, OffsetDateTime.class).toInstant());
    }
}
