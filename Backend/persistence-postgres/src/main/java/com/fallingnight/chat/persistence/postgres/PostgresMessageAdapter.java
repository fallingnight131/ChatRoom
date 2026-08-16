package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.messaging.MessageHistoryPort;
import com.fallingnight.chat.application.messaging.ConversationEntryHistoryPort;
import com.fallingnight.chat.application.messaging.ConversationEntryHistoryResult;
import com.fallingnight.chat.application.messaging.ConversationHistoryEntry;
import com.fallingnight.chat.application.messaging.MessageHistoryQuery;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.MessageMention;
import com.fallingnight.chat.application.messaging.MessageReplyReference;
import com.fallingnight.chat.application.messaging.MessageReactionKind;
import com.fallingnight.chat.application.messaging.MessageSubmission;
import com.fallingnight.chat.application.messaging.MessageSubmissionPort;
import com.fallingnight.chat.application.messaging.MessageSubmissionResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import com.fallingnight.chat.application.notification.WebPushDeliveryPolicy;
import com.fallingnight.chat.application.notification.WebPushNotificationIntent;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;

/** Atomic message append/idempotency and active-member sequence history adapter. */
public final class PostgresMessageAdapter implements MessageSubmissionPort, MessageHistoryPort,
        ConversationEntryHistoryPort {
    private final DataSource dataSource;
    private final Supplier<UUID> uuidSupplier;
    private final WebPushDeliveryPolicy webPushDeliveryPolicy;

    public PostgresMessageAdapter(DataSource dataSource) {
        this(dataSource, UUID::randomUUID, WebPushDeliveryPolicy.DEFAULT);
    }

    public PostgresMessageAdapter(
            DataSource dataSource, WebPushDeliveryPolicy webPushDeliveryPolicy) {
        this(dataSource, UUID::randomUUID, webPushDeliveryPolicy);
    }

    PostgresMessageAdapter(DataSource dataSource, Supplier<UUID> uuidSupplier) {
        this(dataSource, uuidSupplier, WebPushDeliveryPolicy.DEFAULT);
    }

    PostgresMessageAdapter(
            DataSource dataSource,
            Supplier<UUID> uuidSupplier,
            WebPushDeliveryPolicy webPushDeliveryPolicy) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
        this.webPushDeliveryPolicy = Objects.requireNonNull(
                webPushDeliveryPolicy, "webPushDeliveryPolicy");
    }

    @Override
    public MessageSubmissionResult submit(MessageSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        byte[] payload = submission.payload();
        byte[] payloadHash = sha256(payload);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!authorizedSender(connection, submission)) {
                    connection.rollback();
                    return MessageSubmissionResult.Rejected.NOT_AUTHORIZED;
                }
                Optional<ExistingMessage> existing = findExisting(connection, submission);
                if (existing.isPresent()) {
                    connection.rollback();
                    return existingResult(existing.orElseThrow(), submission, payload, payloadHash);
                }
                if (!PostgresAccountBlockPolicy.allowsConversationWrite(
                        connection, submission.conversationId(), submission.senderAccountId())) {
                    connection.rollback();
                    return MessageSubmissionResult.Rejected.NOT_AUTHORIZED;
                }
                long sequence = allocateSequence(connection, submission.conversationId());
                if (!authorizedMentionTargets(connection, submission)) {
                    connection.rollback();
                    return MessageSubmissionResult.Rejected.NOT_AUTHORIZED;
                }
                Optional<MessageReplyReference> reply = resolveReply(connection, submission);
                if (submission.replyToMessageId().isPresent() && reply.isEmpty()) {
                    connection.rollback();
                    return MessageSubmissionResult.Rejected.NOT_AUTHORIZED;
                }
                UUID messageId = Objects.requireNonNull(uuidSupplier.get(), "messageId");
                Optional<Instant> insertedAt = insert(
                        connection,
                        messageId,
                        sequence,
                        submission,
                        payload,
                        payloadHash,
                        reply,
                        webPushDeliveryPolicy);
                if (insertedAt.isPresent()) {
                    connection.commit();
                    return new MessageSubmissionResult.Accepted(
                            messageId, sequence, insertedAt.orElseThrow(), false, reply);
                }
                connection.rollback();
                ExistingMessage raced = findExistingAfterRace(submission);
                return existingResult(raced, submission, payload, payloadHash);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new MessagePersistenceException("message submission failed", exception);
        } finally {
            Arrays.fill(payloadHash, (byte) 0);
        }
    }

    @Override
    public MessageHistoryResult readAfter(MessageHistoryQuery query) {
        ConversationEntryHistoryResult result = readEntriesAfter(query);
        if (result == ConversationEntryHistoryResult.Rejected.NOT_AUTHORIZED) {
            return MessageHistoryResult.Rejected.NOT_AUTHORIZED;
        }
        ConversationEntryHistoryResult.Page page = (ConversationEntryHistoryResult.Page) result;
        List<StoredMessage> messages = page.entries().stream()
                .filter(ConversationHistoryEntry.Message.class::isInstance)
                .map(ConversationHistoryEntry.Message.class::cast)
                .map(ConversationHistoryEntry.Message::value)
                .toList();
        return new MessageHistoryResult.Page(
                messages, page.entries(), page.nextSequence(), page.latestSequence(),
                page.hasMore());
    }

    @Override
    public ConversationEntryHistoryResult readEntriesAfter(MessageHistoryQuery query) {
        Objects.requireNonNull(query, "query");
        try (Connection connection = dataSource.getConnection()) {
            Optional<Long> latest = authorizedLatestSequence(connection, query);
            if (latest.isEmpty()) {
                return ConversationEntryHistoryResult.Rejected.NOT_AUTHORIZED;
            }
            List<ConversationHistoryEntry> entries = readEntries(connection, query);
            boolean hasMore = entries.size() > query.limit();
            if (hasMore) entries.removeLast();
            long next = entries.isEmpty()
                    ? query.afterSequence()
                    : entries.getLast().conversationSequence();
            return new ConversationEntryHistoryResult.Page(
                    entries, next, latest.orElseThrow(), hasMore);
        } catch (SQLException exception) {
            throw new MessagePersistenceException(
                    "conversation entry history read failed", exception);
        }
    }

    private static List<ConversationHistoryEntry> readEntries(
            Connection connection, MessageHistoryQuery query) throws SQLException {
        String sql = """
                SELECT ce.conversation_sequence, ce.entry_kind, ce.occurred_at,
                       m.id, m.sender_account_id, m.sender_device_id,
                       m.client_message_id, m.message_type, m.payload, m.accepted_at,
                       r.message_id, r.actor_account_id, r.source,
                       d.actor_account_id, d.source, d.mode, d.client_operation_id,
                       CASE WHEN d.source = 'V1_IMPORT' THEN ARRAY(
                           SELECT lm.message_id
                           FROM jsonb_array_elements_text(d.message_ids)
                                WITH ORDINALITY AS source_id(value, position)
                           JOIN chat.legacy_v1_message_map lm
                             ON lm.legacy_kind = 'ROOM'
                            AND lm.legacy_conversation_id = ldm.legacy_room_id
                            AND lm.legacy_message_id = source_id.value::bigint
                           ORDER BY source_id.position)
                       WHEN d.source = 'V2' THEN ARRAY(
                           SELECT source_id.value::uuid
                           FROM jsonb_array_elements_text(d.message_ids)
                                WITH ORDINALITY AS source_id(value, position)
                           ORDER BY source_id.position)
                       ELSE ARRAY[]::uuid[] END AS deletion_message_ids,
                       d.cutoff_epoch_ms, d.deleted_count,
                       d.operator_name_snapshot,
                       rr.target_message_id, rr.target_conversation_sequence,
                       rr.target_sender_account_id,
                       reaction.message_id, reaction.actor_account_id,
                       reaction.reaction, reaction.active,
                       reaction.client_operation_id,
                       pin.message_id, pin.actor_account_id, pin.pinned,
                       pin.client_operation_id,
                       m.content_revision, m.edited_at,
                       edit.message_id, edit.content_revision, edit.content_type,
                       edit.content, edit.content_erased_at IS NOT NULL,
                       edit.actor_account_id, edit.client_operation_id,
                       ARRAY(SELECT mention.target_account_id
                           FROM chat.message_mention mention
                           WHERE mention.conversation_id = m.conversation_id
                             AND mention.message_id = m.id
                           ORDER BY mention.mention_ordinal),
                       ARRAY(SELECT mention.start_utf8_byte
                           FROM chat.message_mention mention
                           WHERE mention.conversation_id = m.conversation_id
                             AND mention.message_id = m.id
                           ORDER BY mention.mention_ordinal),
                       ARRAY(SELECT mention.length_utf8_bytes
                           FROM chat.message_mention mention
                           WHERE mention.conversation_id = m.conversation_id
                             AND mention.message_id = m.id
                           ORDER BY mention.mention_ordinal),
                       ARRAY(SELECT mention.target_account_id
                           FROM chat.message_edit_event_mention mention
                           WHERE mention.conversation_id = edit.conversation_id
                             AND mention.conversation_sequence = edit.conversation_sequence
                           ORDER BY mention.mention_ordinal),
                       ARRAY(SELECT mention.start_utf8_byte
                           FROM chat.message_edit_event_mention mention
                           WHERE mention.conversation_id = edit.conversation_id
                             AND mention.conversation_sequence = edit.conversation_sequence
                           ORDER BY mention.mention_ordinal),
                       ARRAY(SELECT mention.length_utf8_bytes
                           FROM chat.message_edit_event_mention mention
                           WHERE mention.conversation_id = edit.conversation_id
                             AND mention.conversation_sequence = edit.conversation_sequence
                           ORDER BY mention.mention_ordinal),
                       m.forwarded
                FROM chat.conversation_entry ce
                LEFT JOIN chat.message m
                  ON ce.entry_kind = 'MESSAGE'
                 AND m.conversation_id = ce.conversation_id
                 AND m.conversation_sequence = ce.conversation_sequence
                LEFT JOIN chat.message_recall_event r
                  ON ce.entry_kind = 'MESSAGE_RECALLED'
                 AND r.conversation_id = ce.conversation_id
                 AND r.conversation_sequence = ce.conversation_sequence
                LEFT JOIN chat.messages_deleted_event d
                  ON ce.entry_kind = 'MESSAGES_DELETED'
                 AND d.conversation_id = ce.conversation_id
                 AND d.conversation_sequence = ce.conversation_sequence
                LEFT JOIN chat.message_reply_reference rr
                  ON ce.entry_kind = 'MESSAGE'
                 AND rr.message_id = m.id
                LEFT JOIN chat.message_reaction_event reaction
                  ON ce.entry_kind = 'MESSAGE_REACTION_CHANGED'
                 AND reaction.conversation_id = ce.conversation_id
                 AND reaction.conversation_sequence = ce.conversation_sequence
                LEFT JOIN chat.message_pin_event pin
                  ON ce.entry_kind = 'MESSAGE_PIN_CHANGED'
                 AND pin.conversation_id = ce.conversation_id
                 AND pin.conversation_sequence = ce.conversation_sequence
                LEFT JOIN chat.message_edit_event edit
                  ON ce.entry_kind = 'MESSAGE_EDITED'
                 AND edit.conversation_id = ce.conversation_id
                 AND edit.conversation_sequence = ce.conversation_sequence
                LEFT JOIN chat.legacy_v1_deletion_event_map ldm
                  ON d.source = 'V1_IMPORT'
                 AND ldm.conversation_id = ce.conversation_id
                 AND ldm.conversation_sequence = ce.conversation_sequence
                WHERE ce.conversation_id = ? AND ce.conversation_sequence > ?
                ORDER BY ce.conversation_sequence ASC LIMIT ?
                """;
        List<ConversationHistoryEntry> entries = new ArrayList<>(query.limit() + 1);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, query.conversationId());
            statement.setLong(2, query.afterSequence());
            statement.setInt(3, query.limit() + 1);
            statement.setFetchSize(query.limit() + 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    entries.add(readEntry(result, query.conversationId()));
                }
            }
        }
        return entries;
    }

    private static ConversationHistoryEntry readEntry(
            ResultSet result, UUID conversationId) throws SQLException {
        long sequence = result.getLong(1);
        return switch (result.getString(2)) {
            case "MESSAGE" -> new ConversationHistoryEntry.Message(new StoredMessage(
                    result.getObject(4, UUID.class), conversationId, sequence,
                    result.getObject(5, UUID.class), result.getObject(6, UUID.class),
                    result.getString(7), result.getInt(8), result.getBytes(9),
                    result.getObject(10, OffsetDateTime.class).toInstant(),
                    readReply(result, 22), result.getInt(34),
                    Optional.ofNullable(result.getObject(35, OffsetDateTime.class))
                            .map(OffsetDateTime::toInstant),
                    readMentions(result, 43), result.getBoolean(49)));
            case "MESSAGE_RECALLED" -> new ConversationHistoryEntry.Recall(
                    conversationId, sequence, result.getObject(11, UUID.class),
                    result.getObject(12, UUID.class), result.getString(13),
                    Optional.ofNullable(result.getObject(3, OffsetDateTime.class))
                            .map(OffsetDateTime::toInstant));
            case "MESSAGES_DELETED" -> new ConversationHistoryEntry.Deletion(
                    conversationId, sequence, result.getObject(14, UUID.class),
                    result.getString(15), result.getString(16), result.getString(17),
                    uuidList(result.getArray(18)), result.getLong(19), result.getInt(20),
                    result.getString(21),
                    result.getObject(3, OffsetDateTime.class).toInstant());
            case "MESSAGE_REACTION_CHANGED" -> new ConversationHistoryEntry.Reaction(
                    conversationId, sequence, result.getObject(25, UUID.class),
                    result.getObject(26, UUID.class),
                    MessageReactionKind.valueOf(result.getString(27)),
                    result.getBoolean(28), result.getString(29),
                    result.getObject(3, OffsetDateTime.class).toInstant());
            case "MESSAGE_PIN_CHANGED" -> new ConversationHistoryEntry.Pin(
                    conversationId, sequence, result.getObject(30, UUID.class),
                    result.getObject(31, UUID.class), result.getBoolean(32),
                    result.getString(33),
                    result.getObject(3, OffsetDateTime.class).toInstant());
            case "MESSAGE_EDITED" -> new ConversationHistoryEntry.Edit(
                    conversationId, sequence, result.getObject(36, UUID.class),
                    result.getInt(37), result.getInt(38),
                    Optional.ofNullable(result.getBytes(39)).orElseGet(() -> new byte[0]),
                    result.getBoolean(40), result.getObject(41, UUID.class),
                    result.getString(42),
                    result.getObject(3, OffsetDateTime.class).toInstant(),
                    readMentions(result, 46));
            default -> throw new SQLException("unsupported conversation entry kind");
        };
    }

    private static List<UUID> uuidList(java.sql.Array array) throws SQLException {
        if (array == null) return List.of();
        try {
            Object[] values = (Object[]) array.getArray();
            List<UUID> result = new ArrayList<>(values.length);
            for (Object value : values) result.add((UUID) value);
            return List.copyOf(result);
        } finally {
            array.free();
        }
    }

    private static boolean authorizedSender(Connection connection, MessageSubmission submission)
            throws SQLException {
        String sql = "SELECT 1 FROM chat.conversation_member cm "
                + "JOIN chat.conversation c ON c.id = cm.conversation_id "
                + "LEFT JOIN chat.group_lifecycle lifecycle ON lifecycle.conversation_id = c.id "
                + "JOIN chat.device d ON d.account_id = cm.account_id "
                + "JOIN chat.account a ON a.id = cm.account_id "
                + "WHERE cm.conversation_id = ? AND cm.account_id = ? AND cm.left_at IS NULL "
                + "AND d.id = ? AND d.revoked_at IS NULL AND a.disabled_at IS NULL "
                + "AND (c.kind = 'DIRECT' OR (lifecycle.conversation_id IS NOT NULL "
                + "AND lifecycle.closed_at IS NULL))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, submission.conversationId());
            statement.setObject(2, submission.senderAccountId());
            statement.setObject(3, submission.senderDeviceId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Optional<Long> authorizedLatestSequence(
            Connection connection, MessageHistoryQuery query) throws SQLException {
        String sql = "SELECT c.next_sequence - 1 FROM chat.conversation c "
                + "JOIN chat.conversation_member cm ON cm.conversation_id = c.id "
                + "LEFT JOIN chat.group_lifecycle lifecycle ON lifecycle.conversation_id = c.id "
                + "JOIN chat.account a ON a.id = cm.account_id "
                + "WHERE c.id = ? AND cm.account_id = ? AND cm.left_at IS NULL "
                + "AND a.disabled_at IS NULL "
                + "AND (c.kind = 'DIRECT' OR (lifecycle.conversation_id IS NOT NULL "
                + "AND lifecycle.closed_at IS NULL))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, query.conversationId());
            statement.setObject(2, query.accountId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getLong(1)) : Optional.empty();
            }
        }
    }

    private static Optional<ExistingMessage> findExisting(
            Connection connection, MessageSubmission submission) throws SQLException {
        String sql = "SELECT m.id, m.conversation_id, m.conversation_sequence, "
                + "m.sender_device_id, m.message_type, m.payload, m.payload_sha256, "
                + "m.accepted_at, rr.target_message_id, rr.target_conversation_sequence, "
                + "rr.target_sender_account_id FROM chat.message m "
                + "LEFT JOIN chat.message_reply_reference rr ON rr.message_id = m.id "
                + "WHERE m.sender_account_id = ? AND m.client_message_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, submission.senderAccountId());
            statement.setString(2, submission.clientMessageId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(readExisting(connection, result))
                        : Optional.empty();
            }
        }
    }

    private ExistingMessage findExistingAfterRace(MessageSubmission submission) {
        try (Connection connection = dataSource.getConnection()) {
            return findExisting(connection, submission).orElseThrow(
                    () -> new MessagePersistenceException(
                            "idempotency conflict row disappeared",
                            new IllegalStateException("missing durable conflict row")));
        } catch (SQLException exception) {
            throw new MessagePersistenceException("idempotency conflict read failed", exception);
        }
    }

    private static long allocateSequence(Connection connection, UUID conversationId)
            throws SQLException {
        String sql = "UPDATE chat.conversation SET next_sequence = next_sequence + 1, "
                + "updated_at = transaction_timestamp() WHERE id = ? "
                + "RETURNING next_sequence - 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("authorized conversation disappeared");
                }
                return result.getLong(1);
            }
        }
    }

    private static Optional<Instant> insert(
            Connection connection,
            UUID messageId,
            long sequence,
            MessageSubmission submission,
            byte[] payload,
            byte[] payloadHash,
            Optional<MessageReplyReference> reply,
            WebPushDeliveryPolicy webPushDeliveryPolicy) throws SQLException {
        insertConversationEntry(connection, submission.conversationId(), sequence);
        String sql = "INSERT INTO chat.message(id, conversation_id, conversation_sequence, "
                + "sender_account_id, sender_device_id, client_message_id, message_type, "
                + "payload, payload_sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (sender_account_id, client_message_id) DO NOTHING "
                + "RETURNING accepted_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, messageId);
            statement.setObject(2, submission.conversationId());
            statement.setLong(3, sequence);
            statement.setObject(4, submission.senderAccountId());
            statement.setObject(5, submission.senderDeviceId());
            statement.setString(6, submission.clientMessageId());
            statement.setInt(7, submission.messageType());
            statement.setBytes(8, payload);
            statement.setBytes(9, payloadHash);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                Instant acceptedAt = result.getObject(1, OffsetDateTime.class).toInstant();
                if (reply.isPresent()) {
                    insertReplyReference(connection, messageId, submission.conversationId(),
                            reply.orElseThrow());
                }
                insertMentions(connection, submission.conversationId(), messageId,
                        submission.mentions());
                insertOutbox(connection, messageId, submission.conversationId(), sequence);
                if (webPushDeliveryPolicy.enabled()) {
                    insertWebPushOutbox(connection, messageId, submission, acceptedAt);
                }
                return Optional.of(acceptedAt);
            }
        }
    }

    private static void insertReplyReference(
            Connection connection,
            UUID messageId,
            UUID conversationId,
            MessageReplyReference reply) throws SQLException {
        String sql = "INSERT INTO chat.message_reply_reference(message_id, conversation_id, "
                + "target_message_id, target_conversation_sequence, target_sender_account_id) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, messageId);
            statement.setObject(2, conversationId);
            statement.setObject(3, reply.targetMessageId());
            statement.setLong(4, reply.targetConversationSequence());
            statement.setObject(5, reply.targetSenderAccountId());
            statement.executeUpdate();
        }
    }

    private static void insertMentions(
            Connection connection, UUID conversationId, UUID messageId,
            List<MessageMention> mentions) throws SQLException {
        if (mentions.isEmpty()) return;
        String sql = "INSERT INTO chat.message_mention(conversation_id,message_id,"
                + "mention_ordinal,target_account_id,start_utf8_byte,length_utf8_bytes) "
                + "VALUES (?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < mentions.size(); index++) {
                MessageMention mention = mentions.get(index);
                statement.setObject(1, conversationId);
                statement.setObject(2, messageId);
                statement.setInt(3, index);
                statement.setObject(4, mention.targetAccountId());
                statement.setInt(5, mention.startUtf8Byte());
                statement.setInt(6, mention.lengthUtf8Bytes());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertConversationEntry(
            Connection connection, UUID conversationId, long sequence) throws SQLException {
        String sql = "INSERT INTO chat.conversation_entry("
                + "conversation_id, conversation_sequence, entry_kind, occurred_at) "
                + "VALUES (?, ?, 'MESSAGE', transaction_timestamp())";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, conversationId);
            statement.setLong(2, sequence);
            statement.executeUpdate();
        }
    }

    private static void insertOutbox(
            Connection connection, UUID eventId, UUID conversationId, long sequence)
            throws SQLException {
        String sql = "INSERT INTO chat.conversation_event_outbox("
                + "event_id, conversation_id, conversation_sequence) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, eventId);
            statement.setObject(2, conversationId);
            statement.setLong(3, sequence);
            statement.executeUpdate();
        }
    }

    private static void insertWebPushOutbox(
            Connection connection,
            UUID messageId,
            MessageSubmission submission,
            Instant acceptedAt) throws SQLException {
        String sql = "INSERT INTO chat.web_push_notification_outbox("
                + "message_id, conversation_id, sender_account_id, "
                + "mentioned_account_ids, committed_at, expires_at, available_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        List<UUID> mentionedAccountIds = submission.mentions().stream()
                .map(MessageMention::targetAccountId)
                .distinct()
                .toList();
        java.sql.Array mentions = connection.createArrayOf(
                "uuid", mentionedAccountIds.toArray());
        OffsetDateTime committedAt = OffsetDateTime.ofInstant(acceptedAt, ZoneOffset.UTC);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, messageId);
            statement.setObject(2, submission.conversationId());
            statement.setObject(3, submission.senderAccountId());
            statement.setArray(4, mentions);
            statement.setObject(5, committedAt);
            statement.setObject(6, OffsetDateTime.ofInstant(
                    acceptedAt.plus(WebPushNotificationIntent.MAX_LIFETIME), ZoneOffset.UTC));
            statement.setObject(7, committedAt);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Web Push outbox insertion changed no row");
            }
        } finally {
            mentions.free();
        }
    }

    private static MessageSubmissionResult existingResult(
            ExistingMessage existing,
            MessageSubmission submission,
            byte[] payload,
            byte[] payloadHash) {
        boolean exact = existing.conversationId().equals(submission.conversationId())
                && existing.senderDeviceId().equals(submission.senderDeviceId())
                && existing.messageType() == submission.messageType()
                && existing.reply().map(MessageReplyReference::targetMessageId)
                        .equals(submission.replyToMessageId())
                && existing.mentions().equals(submission.mentions())
                && MessageDigest.isEqual(existing.payloadHash(), payloadHash)
                && Arrays.equals(existing.payload(), payload);
        return exact
                ? new MessageSubmissionResult.Accepted(
                        existing.messageId(),
                        existing.sequence(),
                        existing.acceptedAt(),
                        true,
                        existing.reply())
                : MessageSubmissionResult.Rejected.IDEMPOTENCY_CONFLICT;
    }

    private static ExistingMessage readExisting(
            Connection connection, ResultSet result) throws SQLException {
        UUID conversationId = result.getObject(2, UUID.class);
        UUID messageId = result.getObject(1, UUID.class);
        return new ExistingMessage(
                messageId,
                conversationId,
                result.getLong(3),
                result.getObject(4, UUID.class),
                result.getInt(5),
                result.getBytes(6),
                result.getBytes(7),
                result.getObject(8, OffsetDateTime.class).toInstant(),
                readReply(result, 9),
                readMentions(connection, conversationId, messageId));
    }

    private static boolean authorizedMentionTargets(
            Connection connection, MessageSubmission submission) throws SQLException {
        if (submission.mentions().isEmpty()) return true;
        List<UUID> targets = submission.mentions().stream()
                .map(MessageMention::targetAccountId).distinct().toList();
        java.sql.Array targetArray = connection.createArrayOf("uuid", targets.toArray());
        String sql = "SELECT cm.account_id FROM chat.conversation_member cm "
                + "JOIN chat.account a ON a.id = cm.account_id "
                + "WHERE cm.conversation_id = ? AND cm.account_id = ANY (?) "
                + "AND cm.left_at IS NULL AND a.disabled_at IS NULL FOR KEY SHARE OF cm, a";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, submission.conversationId());
            statement.setArray(2, targetArray);
            int found = 0;
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) found++;
            }
            return found == targets.size();
        } finally {
            targetArray.free();
        }
    }

    private static List<MessageMention> readMentions(
            Connection connection, UUID conversationId, UUID messageId) throws SQLException {
        String sql = "SELECT target_account_id,start_utf8_byte,length_utf8_bytes "
                + "FROM chat.message_mention WHERE conversation_id = ? AND message_id = ? "
                + "ORDER BY mention_ordinal";
        List<MessageMention> mentions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, conversationId);
            statement.setObject(2, messageId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    mentions.add(new MessageMention(result.getObject(1, UUID.class),
                            result.getInt(2), result.getInt(3)));
                }
            }
        }
        return List.copyOf(mentions);
    }

    private static List<MessageMention> readMentions(
            ResultSet result, int firstColumn) throws SQLException {
        List<UUID> targets = uuidList(result.getArray(firstColumn));
        List<Integer> starts = integerList(result.getArray(firstColumn + 1));
        List<Integer> lengths = integerList(result.getArray(firstColumn + 2));
        if (targets.size() != starts.size() || targets.size() != lengths.size()) {
            throw new SQLException("mention projection cardinality differs");
        }
        List<MessageMention> mentions = new ArrayList<>(targets.size());
        for (int index = 0; index < targets.size(); index++) {
            mentions.add(new MessageMention(targets.get(index), starts.get(index),
                    lengths.get(index)));
        }
        return List.copyOf(mentions);
    }

    private static List<Integer> integerList(java.sql.Array array) throws SQLException {
        if (array == null) return List.of();
        try {
            Object[] values = (Object[]) array.getArray();
            List<Integer> result = new ArrayList<>(values.length);
            for (Object value : values) result.add(((Number) value).intValue());
            return List.copyOf(result);
        } finally {
            array.free();
        }
    }

    private static Optional<MessageReplyReference> resolveReply(
            Connection connection, MessageSubmission submission) throws SQLException {
        if (submission.replyToMessageId().isEmpty()) return Optional.empty();
        String sql = "SELECT m.id, m.conversation_sequence, m.sender_account_id "
                + "FROM chat.message m WHERE m.id = ? AND m.conversation_id = ? "
                + "AND NOT EXISTS (SELECT 1 FROM chat.message_recall_event r "
                + "WHERE r.conversation_id = m.conversation_id AND r.message_id = m.id)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, submission.replyToMessageId().orElseThrow());
            statement.setObject(2, submission.conversationId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(new MessageReplyReference(
                                result.getObject(1, UUID.class), result.getLong(2),
                                result.getObject(3, UUID.class)))
                        : Optional.empty();
            }
        }
    }

    private static Optional<MessageReplyReference> readReply(
            ResultSet result, int firstColumn) throws SQLException {
        UUID target = result.getObject(firstColumn, UUID.class);
        if (target == null) return Optional.empty();
        return Optional.of(new MessageReplyReference(
                target, result.getLong(firstColumn + 1),
                result.getObject(firstColumn + 2, UUID.class)));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record ExistingMessage(
            UUID messageId,
            UUID conversationId,
            long sequence,
            UUID senderDeviceId,
            int messageType,
            byte[] payload,
            byte[] payloadHash,
            Instant acceptedAt,
            Optional<MessageReplyReference> reply,
            List<MessageMention> mentions) {}
}
