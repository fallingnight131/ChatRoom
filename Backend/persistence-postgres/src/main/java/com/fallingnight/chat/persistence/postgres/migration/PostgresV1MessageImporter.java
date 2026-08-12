package com.fallingnight.chat.persistence.postgres.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** Strict PostgreSQL preview boundary for the verified V1 historical-message plan. */
public final class PostgresV1MessageImporter {
    private final DataSource dataSource;

    public PostgresV1MessageImporter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public V1MessageImportReport preview(V1MessageTargetImportPlan plan) {
        Objects.requireNonNull(plan, "plan");
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                Comparison comparison = compare(connection, plan);
                connection.commit();
                return comparison.report(plan);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new V1MessageImportException("V1 message target preview failed", exception);
        }
    }

    private static Comparison compare(Connection connection, V1MessageTargetImportPlan plan)
            throws SQLException {
        List<V1MessageTargetIssue> issues = new ArrayList<>();
        Map<UUID, Long> desiredNext = new HashMap<>();
        for (PlannedV1ConversationCursor cursor : plan.conversationCursors()) {
            desiredNext.put(cursor.conversationId(), cursor.targetNextSequence());
            compareConversation(connection, cursor, issues);
        }
        int insertDevices = 0;
        int existingDevices = 0;
        for (PlannedV1LegacyDevice device : plan.legacyDevices()) {
            TargetDisposition disposition = compareDevice(connection, device, issues);
            if (disposition == TargetDisposition.ABSENT) insertDevices++;
            if (disposition == TargetDisposition.EXACT) existingDevices++;
        }

        int insertMessages = 0;
        int existingMessages = 0;
        int insertEntries = 0;
        int existingEntries = 0;
        Map<UUID, Set<Long>> expectedEntries = new HashMap<>();
        Map<UUID, Set<UUID>> expectedMessages = new HashMap<>();
        for (PlannedV1HistoricalMessage message : plan.messages()) {
            expectedEntries.computeIfAbsent(message.conversationId(), ignored -> new HashSet<>())
                    .add(message.creationSequence());
            expectedMessages.computeIfAbsent(message.conversationId(), ignored -> new HashSet<>())
                    .add(message.messageId());
            TargetDisposition creation = compareEntry(
                    connection, message.conversationId(), message.creationSequence(),
                    "MESSAGE", message.acceptedAt(), message, issues);
            if (creation == TargetDisposition.ABSENT) insertEntries++;
            if (creation == TargetDisposition.EXACT) existingEntries++;
            TargetDisposition stored = compareMessage(connection, message, issues);
            if (stored == TargetDisposition.ABSENT) insertMessages++;
            if (stored == TargetDisposition.EXACT) existingMessages++;
            compareMessageMapping(connection, message, issues);
            if (message.recalled()) {
                long mutation = Objects.requireNonNull(message.mutationSequence());
                expectedEntries.get(message.conversationId()).add(mutation);
                TargetDisposition recallEntry = compareEntry(
                        connection, message.conversationId(), mutation,
                        "MESSAGE_RECALLED", null, message, issues);
                if (recallEntry == TargetDisposition.ABSENT) insertEntries++;
                if (recallEntry == TargetDisposition.EXACT) existingEntries++;
                compareRecall(connection, message, issues);
            }
        }
        for (PlannedV1DeletionEvent event : plan.deletionEvents()) {
            expectedEntries.computeIfAbsent(event.conversationId(), ignored -> new HashSet<>())
                    .add(event.conversationSequence());
            TargetDisposition deletionEntry = compareEntry(
                    connection, event.conversationId(), event.conversationSequence(),
                    "MESSAGES_DELETED", event.occurredAt(), event, issues);
            if (deletionEntry == TargetDisposition.ABSENT) insertEntries++;
            if (deletionEntry == TargetDisposition.EXACT) existingEntries++;
            compareDeletion(connection, event, issues);
        }
        compareUnexpectedRows(connection, expectedEntries, expectedMessages, issues);

        int updateReads = 0;
        int existingReads = 0;
        for (PlannedV1MemberReadCursor cursor : plan.memberReadCursors()) {
            TargetDisposition read = compareReadCursor(connection, cursor, issues);
            if (read == TargetDisposition.ABSENT) updateReads++;
            if (read == TargetDisposition.EXACT) existingReads++;
        }
        return new Comparison(
                insertMessages, existingMessages, insertEntries, existingEntries,
                insertDevices, existingDevices, updateReads, existingReads, List.copyOf(issues));
    }

    private static void compareConversation(
            Connection connection,
            PlannedV1ConversationCursor cursor,
            List<V1MessageTargetIssue> issues) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.next_sequence, m.legacy_kind, m.legacy_conversation_id
                FROM chat.conversation c
                JOIN chat.legacy_v1_conversation_map m ON m.conversation_id = c.id
                WHERE c.id = ?
                """)) {
            statement.setObject(1, cursor.conversationId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    issues.add(issue(null, 0, 0, "TARGET_CONVERSATION_MISSING",
                            "target conversation mapping must be imported first"));
                    return;
                }
                long next = result.getLong("next_sequence");
                boolean mappingMatches = result.getString("legacy_kind")
                                .equals(cursor.legacyKind().name())
                        && result.getLong("legacy_conversation_id")
                                == cursor.legacyConversationId();
                if (!mappingMatches) {
                    issues.add(issue(cursor.legacyKind(), cursor.legacyConversationId(), 0,
                            "TARGET_CONVERSATION_MAPPING_CONFLICT",
                            "target conversation mapping differs from message plan"));
                }
                if (next != 1 && next != cursor.targetNextSequence()) {
                    issues.add(issue(cursor.legacyKind(), cursor.legacyConversationId(), 0,
                            "TARGET_CONVERSATION_SEQUENCE_CONFLICT",
                            "target conversation high watermark differs from message plan"));
                }
                if (result.next()) {
                    issues.add(issue(cursor.legacyKind(), cursor.legacyConversationId(), 0,
                            "TARGET_CONVERSATION_MAPPING_CONFLICT",
                            "target conversation mapping is not unique"));
                }
            }
        }
    }

    private static TargetDisposition compareDevice(
            Connection connection,
            PlannedV1LegacyDevice planned,
            List<V1MessageTargetIssue> issues) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, account_id, client_device_id, platform, revoked_at
                FROM chat.device
                WHERE id = ? OR (account_id = ? AND client_device_id = ?)
                """)) {
            statement.setObject(1, planned.deviceId());
            statement.setObject(2, planned.accountId());
            statement.setString(3, planned.clientDeviceId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return TargetDisposition.ABSENT;
                boolean exact = result.getObject("id", UUID.class).equals(planned.deviceId())
                        && result.getObject("account_id", UUID.class).equals(planned.accountId())
                        && result.getString("client_device_id").equals(planned.clientDeviceId())
                        && result.getString("platform").equals("LEGACY")
                        && result.getObject("revoked_at") == null
                        && !result.next();
                if (exact) return TargetDisposition.EXACT;
                issues.add(issue(null, 0, 0, "TARGET_LEGACY_DEVICE_CONFLICT",
                        "target legacy provenance device differs from plan"));
                return TargetDisposition.CONFLICT;
            }
        }
    }

    private static TargetDisposition compareEntry(
            Connection connection,
            UUID conversationId,
            long sequence,
            String kind,
            java.time.Instant occurredAt,
            Object source,
            List<V1MessageTargetIssue> issues) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT entry_kind, occurred_at FROM chat.conversation_entry
                WHERE conversation_id = ? AND conversation_sequence = ?
                """)) {
            statement.setObject(1, conversationId);
            statement.setLong(2, sequence);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return TargetDisposition.ABSENT;
                OffsetDateTime targetTime = result.getObject("occurred_at", OffsetDateTime.class);
                boolean timeMatches = occurredAt == null
                        ? targetTime == null
                        : targetTime != null && targetTime.toInstant().equals(occurredAt);
                if (kind.equals(result.getString("entry_kind")) && timeMatches && !result.next()) {
                    return TargetDisposition.EXACT;
                }
                issues.add(issue(source, "TARGET_ENTRY_CONFLICT",
                        "target conversation entry differs from plan"));
                return TargetDisposition.CONFLICT;
            }
        }
    }

    private static TargetDisposition compareMessage(
            Connection connection,
            PlannedV1HistoricalMessage planned,
            List<V1MessageTargetIssue> issues) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, conversation_id, conversation_sequence, sender_account_id,
                       sender_device_id, client_message_id, message_type, payload,
                       payload_sha256, accepted_at, deleted_at
                FROM chat.message
                WHERE id = ? OR (conversation_id = ? AND conversation_sequence = ?)
                   OR (sender_account_id = ? AND client_message_id = ?)
                """)) {
            statement.setObject(1, planned.messageId());
            statement.setObject(2, planned.conversationId());
            statement.setLong(3, planned.creationSequence());
            statement.setObject(4, planned.senderAccountId());
            statement.setString(5, planned.clientMessageId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return TargetDisposition.ABSENT;
                byte[] payload = planned.text().getBytes(StandardCharsets.UTF_8);
                boolean exact = result.getObject("id", UUID.class).equals(planned.messageId())
                        && result.getObject("conversation_id", UUID.class)
                                .equals(planned.conversationId())
                        && result.getLong("conversation_sequence") == planned.creationSequence()
                        && result.getObject("sender_account_id", UUID.class)
                                .equals(planned.senderAccountId())
                        && result.getObject("sender_device_id", UUID.class)
                                .equals(planned.senderDeviceId())
                        && result.getString("client_message_id").equals(planned.clientMessageId())
                        && result.getInt("message_type") == planned.contentType()
                        && Arrays.equals(result.getBytes("payload"), payload)
                        && MessageDigest.isEqual(result.getBytes("payload_sha256"), sha256(payload))
                        && result.getObject("accepted_at", OffsetDateTime.class).toInstant()
                                .equals(planned.acceptedAt())
                        && result.getObject("deleted_at") == null
                        && !result.next();
                if (exact) return TargetDisposition.EXACT;
                issues.add(issue(planned, "TARGET_MESSAGE_CONFLICT",
                        "target message identity or durable fields differ from plan"));
                return TargetDisposition.CONFLICT;
            }
        }
    }

    private static void compareMessageMapping(
            Connection connection,
            PlannedV1HistoricalMessage planned,
            List<V1MessageTargetIssue> issues) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT legacy_kind, legacy_message_id, legacy_conversation_id,
                       conversation_id, message_id
                FROM chat.legacy_v1_message_map
                WHERE (legacy_kind = ? AND legacy_message_id = ?) OR message_id = ?
                """)) {
            statement.setString(1, planned.legacyKind().name());
            statement.setLong(2, planned.legacyMessageId());
            statement.setObject(3, planned.messageId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return;
                boolean exact = result.getString("legacy_kind").equals(planned.legacyKind().name())
                        && result.getLong("legacy_message_id") == planned.legacyMessageId()
                        && result.getLong("legacy_conversation_id")
                                == planned.legacyConversationId()
                        && result.getObject("conversation_id", UUID.class)
                                .equals(planned.conversationId())
                        && result.getObject("message_id", UUID.class).equals(planned.messageId())
                        && !result.next();
                if (!exact) issues.add(issue(planned, "TARGET_MESSAGE_MAPPING_CONFLICT",
                        "target V1 message mapping differs from plan"));
            }
        }
    }

    private static void compareRecall(
            Connection connection,
            PlannedV1HistoricalMessage planned,
            List<V1MessageTargetIssue> issues) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT message_id, actor_account_id, source
                FROM chat.message_recall_event
                WHERE conversation_id = ? AND conversation_sequence = ?
                """)) {
            statement.setObject(1, planned.conversationId());
            statement.setLong(2, Objects.requireNonNull(planned.mutationSequence()));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return;
                boolean exact = result.getObject("message_id", UUID.class).equals(planned.messageId())
                        && result.getObject("actor_account_id", UUID.class)
                                .equals(planned.senderAccountId())
                        && result.getString("source").equals("V1_IMPORT")
                        && !result.next();
                if (!exact) issues.add(issue(planned, "TARGET_RECALL_EVENT_CONFLICT",
                        "target recall event differs from plan"));
            }
        }
    }

    private static void compareDeletion(
            Connection connection,
            PlannedV1DeletionEvent planned,
            List<V1MessageTargetIssue> issues) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT actor_account_id, source, mode, client_operation_id,
                       command_fingerprint, message_ids::text, file_ids::text,
                       cutoff_epoch_ms, deleted_count, operator_name_snapshot
                FROM chat.messages_deleted_event
                WHERE conversation_id = ? AND conversation_sequence = ?
                """)) {
            statement.setObject(1, planned.conversationId());
            statement.setLong(2, planned.conversationSequence());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    boolean exact = result.getObject("actor_account_id", UUID.class)
                                    .equals(planned.actorAccountId())
                            && result.getString("source").equals("V1_IMPORT")
                            && result.getString("mode").equals(planned.mode())
                            && result.getString("client_operation_id")
                                    .equals(planned.clientOperationId())
                            && result.getString("command_fingerprint")
                                    .equals(planned.commandFingerprint())
                            && jsonEquals(connection, result.getString("message_ids"),
                                    planned.messageIdsJson())
                            && jsonEquals(connection, result.getString("file_ids"),
                                    planned.fileIdsJson())
                            && result.getLong("cutoff_epoch_ms") == planned.cutoffEpochMs()
                            && result.getInt("deleted_count") == planned.deletedCount()
                            && result.getString("operator_name_snapshot")
                                    .equals(nullToEmpty(planned.operatorName()))
                            && !result.next();
                    if (!exact) issues.add(issue(planned, "TARGET_DELETION_EVENT_CONFLICT",
                            "target deletion event differs from plan"));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT legacy_event_id, legacy_room_id, conversation_id,
                       conversation_sequence
                FROM chat.legacy_v1_deletion_event_map
                WHERE legacy_event_id = ? OR (conversation_id = ? AND conversation_sequence = ?)
                """)) {
            statement.setLong(1, planned.legacyEventId());
            statement.setObject(2, planned.conversationId());
            statement.setLong(3, planned.conversationSequence());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return;
                boolean exact = result.getLong("legacy_event_id") == planned.legacyEventId()
                        && result.getLong("legacy_room_id") == planned.legacyRoomId()
                        && result.getObject("conversation_id", UUID.class)
                                .equals(planned.conversationId())
                        && result.getLong("conversation_sequence")
                                == planned.conversationSequence()
                        && !result.next();
                if (!exact) issues.add(issue(planned, "TARGET_DELETION_MAPPING_CONFLICT",
                        "target V1 deletion event mapping differs from plan"));
            }
        }
    }

    private static TargetDisposition compareReadCursor(
            Connection connection,
            PlannedV1MemberReadCursor planned,
            List<V1MessageTargetIssue> issues) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT last_read_sequence FROM chat.conversation_member
                WHERE conversation_id = ? AND account_id = ? AND left_at IS NULL
                """)) {
            statement.setObject(1, planned.conversationId());
            statement.setObject(2, planned.accountId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    issues.add(issue(null, 0, 0, "TARGET_MEMBERSHIP_MISSING",
                            "target active membership must be imported first"));
                    return TargetDisposition.CONFLICT;
                }
                long current = result.getLong(1);
                if (result.next() || (current != 0 && current != planned.targetLastReadSequence())) {
                    issues.add(issue(null, 0, 0, "TARGET_READ_CURSOR_CONFLICT",
                            "target member read cursor differs from plan"));
                    return TargetDisposition.CONFLICT;
                }
                return current == planned.targetLastReadSequence()
                        ? TargetDisposition.EXACT : TargetDisposition.ABSENT;
            }
        }
    }

    private static void compareUnexpectedRows(
            Connection connection,
            Map<UUID, Set<Long>> expectedEntries,
            Map<UUID, Set<UUID>> expectedMessages,
            List<V1MessageTargetIssue> issues) throws SQLException {
        Set<UUID> conversations = new HashSet<>();
        conversations.addAll(expectedEntries.keySet());
        conversations.addAll(expectedMessages.keySet());
        for (UUID conversation : conversations) {
            long entries = count(connection,
                    "SELECT count(*) FROM chat.conversation_entry WHERE conversation_id = ?",
                    conversation);
            long messages = count(connection,
                    "SELECT count(*) FROM chat.message WHERE conversation_id = ?", conversation);
            if (entries > expectedEntries.getOrDefault(conversation, Set.of()).size()
                    || messages > expectedMessages.getOrDefault(conversation, Set.of()).size()) {
                issues.add(issue(null, 0, 0, "TARGET_UNEXPECTED_MESSAGE_STATE",
                        "target conversation contains message state outside the import plan"));
            }
        }
    }

    private static long count(Connection connection, String sql, UUID conversation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, conversation);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("target count returned no row");
                return result.getLong(1);
            }
        }
    }

    private static boolean jsonEquals(Connection connection, String first, String second)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ?::jsonb = ?::jsonb")) {
            statement.setString(1, first);
            statement.setString(2, second);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static V1MessageTargetIssue issue(Object source, String code, String message) {
        if (source instanceof PlannedV1HistoricalMessage value) {
            return issue(value.legacyKind(), value.legacyConversationId(),
                    value.legacyMessageId(), code, message);
        }
        if (source instanceof PlannedV1DeletionEvent value) {
            return issue(com.fallingnight.chat.application.compatibility.v1
                            .LegacyV1ConversationKind.ROOM,
                    value.legacyRoomId(), 0, code, message);
        }
        return issue(null, 0, 0, code, message);
    }

    private static V1MessageTargetIssue issue(
            com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind kind,
            long conversationId, long messageId, String code, String message) {
        return new V1MessageTargetIssue(kind, conversationId, messageId, code, message);
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException failure) {
            original.addSuppressed(failure);
        }
    }

    private enum TargetDisposition { ABSENT, EXACT, CONFLICT }

    private record Comparison(
            int insertMessages,
            int existingMessages,
            int insertEntries,
            int existingEntries,
            int insertDevices,
            int existingDevices,
            int updateReads,
            int existingReads,
            List<V1MessageTargetIssue> issues) {
        V1MessageImportReport report(V1MessageTargetImportPlan plan) {
            return new V1MessageImportReport(
                    plan.stateFingerprintSha256(), plan.payloadFingerprintSha256(),
                    plan.messages().size(),
                    plan.messages().size()
                            + (int) plan.messages().stream().filter(
                                    PlannedV1HistoricalMessage::recalled).count()
                            + plan.deletionEvents().size(),
                    plan.legacyDevices().size(), plan.memberReadCursors().size(),
                    insertMessages, existingMessages, insertEntries, existingEntries,
                    insertDevices, existingDevices, updateReads, existingReads,
                    issues, false, false, null);
        }
    }
}
