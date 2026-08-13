package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.conversation.ConversationParticipant;
import com.fallingnight.chat.application.conversation.ConversationParticipantPage;
import com.fallingnight.chat.application.conversation.ConversationParticipantPort;
import com.fallingnight.chat.application.conversation.ConversationParticipantQuery;
import com.fallingnight.chat.application.conversation.ConversationParticipantResult;
import com.fallingnight.chat.application.conversation.ConversationRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Active-member-authorized directory ordered by stable account identity. */
public final class PostgresConversationParticipantAdapter implements ConversationParticipantPort {
    private static final String SELECT = "WITH authorized AS ("
            + "SELECT 1 FROM chat.conversation_member requester "
            + "JOIN chat.account requester_account ON requester_account.id = requester.account_id "
            + "WHERE requester.conversation_id = ? AND requester.account_id = ? "
            + "AND requester.left_at IS NULL AND requester_account.disabled_at IS NULL) "
            + "SELECT authorized.ok, participant.account_id, account.display_name, participant.role "
            + "FROM (SELECT 1 AS seed) seed "
            + "LEFT JOIN (SELECT 1 AS ok FROM authorized) authorized ON true "
            + "LEFT JOIN LATERAL (SELECT member.account_id, member.role "
            + "FROM chat.conversation_member member "
            + "JOIN chat.account active_account ON active_account.id = member.account_id "
            + "WHERE authorized.ok = 1 AND member.conversation_id = ? AND member.left_at IS NULL "
            + "AND active_account.disabled_at IS NULL AND member.account_id > ? "
            + "ORDER BY member.account_id ASC LIMIT ?) participant ON true "
            + "LEFT JOIN chat.account account ON account.id = participant.account_id "
            + "ORDER BY participant.account_id ASC";

    private final DataSource dataSource;

    public PostgresConversationParticipantAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public ConversationParticipantResult list(ConversationParticipantQuery query) {
        Objects.requireNonNull(query, "query");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT)) {
            statement.setObject(1, query.conversationId());
            statement.setObject(2, query.requesterAccountId());
            statement.setObject(3, query.conversationId());
            statement.setObject(4, query.afterAccountId().orElse(new UUID(0, 0)));
            statement.setInt(5, query.limit() + 1);
            statement.setFetchSize(query.limit() + 1);
            List<ConversationParticipant> rows = new ArrayList<>(query.limit() + 1);
            boolean authorized = false;
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    authorized = result.getObject(1) != null;
                    UUID accountId = result.getObject(2, UUID.class);
                    if (accountId != null) {
                        rows.add(new ConversationParticipant(accountId, result.getString(3),
                                ConversationRole.valueOf(result.getString(4))));
                    }
                }
            }
            if (!authorized) return ConversationParticipantResult.Rejected.NOT_AUTHORIZED;
            boolean hasMore = rows.size() > query.limit();
            if (hasMore) rows.removeLast();
            Optional<UUID> next = rows.isEmpty()
                    ? Optional.empty() : Optional.of(rows.getLast().accountId());
            return new ConversationParticipantResult.Found(new ConversationParticipantPage(
                    query.conversationId(), rows, next, hasMore));
        } catch (SQLException exception) {
            throw new ConversationPersistenceException(
                    "participant directory read failed", exception);
        }
    }
}
