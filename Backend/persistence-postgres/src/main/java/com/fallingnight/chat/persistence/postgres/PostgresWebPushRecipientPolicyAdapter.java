package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.notification.WebPushNotificationIntent;
import com.fallingnight.chat.application.notification.WebPushRecipient;
import com.fallingnight.chat.application.notification.WebPushRecipientPolicyPort;
import com.fallingnight.chat.application.notification.WebPushRecipientResolution;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** PostgreSQL current membership/account/block reauthorization for one committed message. */
public final class PostgresWebPushRecipientPolicyAdapter
        implements WebPushRecipientPolicyPort {
    private final DataSource dataSource;

    public PostgresWebPushRecipientPolicyAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public WebPushRecipientResolution resolve(WebPushNotificationIntent intent, int limit) {
        Objects.requireNonNull(intent, "intent");
        if (limit < 1 || limit > WebPushRecipientResolution.MAX_RECIPIENTS) {
            throw new IllegalArgumentException("invalid Web Push recipient limit");
        }
        String sql = """
                SELECT member.account_id,
                       member.account_id = ANY(notification.mentioned_account_ids)
                FROM chat.message source
                JOIN chat.web_push_notification_outbox notification
                  ON notification.message_id = source.id
                 AND notification.conversation_id = source.conversation_id
                 AND notification.sender_account_id = source.sender_account_id
                JOIN chat.conversation_member member
                  ON member.conversation_id = source.conversation_id
                 AND member.left_at IS NULL
                JOIN chat.account account
                  ON account.id = member.account_id AND account.disabled_at IS NULL
                WHERE source.id = ? AND source.conversation_id = ?
                  AND source.sender_account_id = ? AND source.deleted_at IS NULL
                  AND member.account_id <> source.sender_account_id
                  AND NOT EXISTS (
                      SELECT 1 FROM chat.message_recall_event recall
                      WHERE recall.conversation_id = source.conversation_id
                        AND recall.message_id = source.id)
                  AND NOT EXISTS (
                      SELECT 1 FROM chat.account_block blocked
                      WHERE (blocked.blocker_account_id = source.sender_account_id
                             AND blocked.blocked_account_id = member.account_id)
                         OR (blocked.blocker_account_id = member.account_id
                             AND blocked.blocked_account_id = source.sender_account_id))
                ORDER BY member.account_id
                LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, intent.messageId());
            statement.setObject(2, intent.conversationId());
            statement.setObject(3, intent.senderAccountId());
            statement.setInt(4, limit + 1);
            List<WebPushRecipient> recipients = new ArrayList<>(limit + 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID accountId = result.getObject(1, UUID.class);
                    recipients.add(new WebPushRecipient(
                            accountId, result.getBoolean(2)));
                }
            }
            if (recipients.size() > limit) {
                return WebPushRecipientResolution.Saturated.INSTANCE;
            }
            return new WebPushRecipientResolution.Complete(recipients);
        } catch (SQLException exception) {
            throw new NotificationPersistenceException(
                    "Web Push recipient policy resolution failed", exception);
        }
    }
}
