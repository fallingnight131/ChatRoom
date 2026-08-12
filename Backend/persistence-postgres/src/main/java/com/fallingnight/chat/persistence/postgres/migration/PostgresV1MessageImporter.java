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

/** Strict PostgreSQL preview/apply boundary for verified V1 historical-message data. */
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

    public V1MessageImportReport apply(VerifiedV1MessageImportBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        V1MessageTargetImportPlan plan = new V1MessageTargetImportPlanner().plan(bundle);
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                lockTarget(connection);
                Comparison before = compare(connection, plan);
                if (!before.ready()) {
                    throw new V1MessageImportException(
                            "V1 message target contains blocking conflicts");
                }
                insertDevices(connection, plan.legacyDevices());
                insertCreationEntries(connection, plan.messages());
                insertMessages(connection, plan.messages());
                insertMessageMappings(connection, plan.messages());
                insertRecallEntriesAndEvents(connection, plan.messages());
                insertDeletionEntriesAndEvents(connection, plan.deletionEvents());
                updateReadCursors(connection, plan.memberReadCursors());
                updateConversationCursors(connection, plan.conversationCursors());
                Comparison after = compare(connection, plan);
                if (!after.fullyReconciled(plan)) {
                    throw new V1MessageImportException(
                            "V1 message post-write reconciliation failed");
                }
                bundle.reverify();
                UUID runId = UUID.randomUUID();
                persistProof(connection, runId, bundle, plan, before);
                connection.commit();
                return before.appliedReport(plan, runId);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new V1MessageImportException("V1 message target apply failed", exception);
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
        int missingAuxiliaryRows = 0;
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
            if (compareMessageMapping(connection, message, issues)
                    == TargetDisposition.ABSENT) missingAuxiliaryRows++;
            if (message.recalled()) {
                long mutation = Objects.requireNonNull(message.mutationSequence());
                expectedEntries.get(message.conversationId()).add(mutation);
                TargetDisposition recallEntry = compareEntry(
                        connection, message.conversationId(), mutation,
                        "MESSAGE_RECALLED", null, message, issues);
                if (recallEntry == TargetDisposition.ABSENT) insertEntries++;
                if (recallEntry == TargetDisposition.EXACT) existingEntries++;
                if (compareRecall(connection, message, issues)
                        == TargetDisposition.ABSENT) missingAuxiliaryRows++;
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
            missingAuxiliaryRows += compareDeletion(connection, event, issues);
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
                insertDevices, existingDevices, updateReads, existingReads,
                missingAuxiliaryRows, List.copyOf(issues));
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
        if (!accountExists(connection, planned.accountId())) {
            issues.add(issue(null, 0, 0, "TARGET_ACCOUNT_MISSING",
                    "target legacy device account is absent or disabled"));
            return TargetDisposition.CONFLICT;
        }
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

    private static TargetDisposition compareMessageMapping(
            Connection connection,
            PlannedV1HistoricalMessage planned,
            List<V1MessageTargetIssue> issues) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT legacy_kind, legacy_message_id, legacy_conversation_id,
                       conversation_id, message_id, legacy_content_type
                FROM chat.legacy_v1_message_map
                WHERE (legacy_kind = ? AND legacy_message_id = ?) OR message_id = ?
                """)) {
            statement.setString(1, planned.legacyKind().name());
            statement.setLong(2, planned.legacyMessageId());
            statement.setObject(3, planned.messageId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return TargetDisposition.ABSENT;
                boolean exact = result.getString("legacy_kind").equals(planned.legacyKind().name())
                        && result.getLong("legacy_message_id") == planned.legacyMessageId()
                        && result.getLong("legacy_conversation_id")
                                == planned.legacyConversationId()
                        && result.getObject("conversation_id", UUID.class)
                                .equals(planned.conversationId())
                        && result.getObject("message_id", UUID.class).equals(planned.messageId())
                        && (result.getString("legacy_content_type") == null
                            || result.getString("legacy_content_type")
                                    .equals(planned.legacyContentType()))
                        && !result.next();
                if (!exact) {
                    issues.add(issue(planned, "TARGET_MESSAGE_MAPPING_CONFLICT",
                            "target V1 message mapping differs from plan"));
                    return TargetDisposition.CONFLICT;
                }
                return TargetDisposition.EXACT;
            }
        }
    }

    private static TargetDisposition compareRecall(
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
                if (!result.next()) return TargetDisposition.ABSENT;
                boolean exact = result.getObject("message_id", UUID.class).equals(planned.messageId())
                        && result.getObject("actor_account_id", UUID.class)
                                .equals(planned.senderAccountId())
                        && result.getString("source").equals("V1_IMPORT")
                        && !result.next();
                if (!exact) {
                    issues.add(issue(planned, "TARGET_RECALL_EVENT_CONFLICT",
                            "target recall event differs from plan"));
                    return TargetDisposition.CONFLICT;
                }
                return TargetDisposition.EXACT;
            }
        }
    }

    private static int compareDeletion(
            Connection connection,
            PlannedV1DeletionEvent planned,
            List<V1MessageTargetIssue> issues) throws SQLException {
        if (!accountExists(connection, planned.actorAccountId())) {
            issues.add(issue(planned, "TARGET_DELETION_ACTOR_MISSING",
                    "target deletion actor account is absent or disabled"));
        }
        int missing = 0;
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
                } else missing++;
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
                if (!result.next()) return missing + 1;
                boolean exact = result.getLong("legacy_event_id") == planned.legacyEventId()
                        && result.getLong("legacy_room_id") == planned.legacyRoomId()
                        && result.getObject("conversation_id", UUID.class)
                                .equals(planned.conversationId())
                        && result.getLong("conversation_sequence")
                                == planned.conversationSequence()
                        && !result.next();
                if (!exact) issues.add(issue(planned, "TARGET_DELETION_MAPPING_CONFLICT",
                        "target V1 deletion event mapping differs from plan"));
                return missing;
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

    private static boolean accountExists(Connection connection, UUID accountId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM chat.account WHERE id = ? AND disabled_at IS NULL")) {
            statement.setObject(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && !result.next();
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

    private static void insertDevices(
            Connection connection, List<PlannedV1LegacyDevice> devices) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.device(id, account_id, client_device_id, platform)
                VALUES (?, ?, ?, 'LEGACY') ON CONFLICT DO NOTHING
                """)) {
            for (PlannedV1LegacyDevice value : devices) {
                statement.setObject(1, value.deviceId());
                statement.setObject(2, value.accountId());
                statement.setString(3, value.clientDeviceId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertCreationEntries(
            Connection connection, List<PlannedV1HistoricalMessage> messages) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.conversation_entry(
                    conversation_id, conversation_sequence, entry_kind, occurred_at)
                VALUES (?, ?, 'MESSAGE', ?) ON CONFLICT DO NOTHING
                """)) {
            for (PlannedV1HistoricalMessage value : messages) {
                statement.setObject(1, value.conversationId());
                statement.setLong(2, value.creationSequence());
                statement.setObject(3, OffsetDateTime.ofInstant(
                        value.acceptedAt(), ZoneOffset.UTC));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertMessages(
            Connection connection, List<PlannedV1HistoricalMessage> messages) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.message(
                    id, conversation_id, conversation_sequence, sender_account_id,
                    sender_device_id, client_message_id, message_type, payload,
                    payload_sha256, accepted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            for (PlannedV1HistoricalMessage value : messages) {
                byte[] payload = value.text().getBytes(StandardCharsets.UTF_8);
                statement.setObject(1, value.messageId());
                statement.setObject(2, value.conversationId());
                statement.setLong(3, value.creationSequence());
                statement.setObject(4, value.senderAccountId());
                statement.setObject(5, value.senderDeviceId());
                statement.setString(6, value.clientMessageId());
                statement.setInt(7, value.contentType());
                statement.setBytes(8, payload);
                statement.setBytes(9, sha256(payload));
                statement.setObject(10, OffsetDateTime.ofInstant(
                        value.acceptedAt(), ZoneOffset.UTC));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertMessageMappings(
            Connection connection, List<PlannedV1HistoricalMessage> messages) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.legacy_v1_message_map(
                    legacy_kind, legacy_message_id, legacy_conversation_id,
                    conversation_id, message_id, legacy_content_type)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (legacy_kind, legacy_message_id)
                DO UPDATE SET legacy_content_type = EXCLUDED.legacy_content_type
                WHERE legacy_v1_message_map.message_id = EXCLUDED.message_id
                  AND legacy_v1_message_map.legacy_content_type IS NULL
                """)) {
            for (PlannedV1HistoricalMessage value : messages) {
                statement.setString(1, value.legacyKind().name());
                statement.setLong(2, value.legacyMessageId());
                statement.setLong(3, value.legacyConversationId());
                statement.setObject(4, value.conversationId());
                statement.setObject(5, value.messageId());
                statement.setString(6, value.legacyContentType());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertRecallEntriesAndEvents(
            Connection connection, List<PlannedV1HistoricalMessage> messages) throws SQLException {
        try (PreparedStatement entry = connection.prepareStatement("""
                INSERT INTO chat.conversation_entry(
                    conversation_id, conversation_sequence, entry_kind, occurred_at)
                VALUES (?, ?, 'MESSAGE_RECALLED', NULL) ON CONFLICT DO NOTHING
                """);
                PreparedStatement event = connection.prepareStatement("""
                INSERT INTO chat.message_recall_event(
                    conversation_id, conversation_sequence, message_id,
                    actor_account_id, source)
                VALUES (?, ?, ?, ?, 'V1_IMPORT') ON CONFLICT DO NOTHING
                """)) {
            for (PlannedV1HistoricalMessage value : messages) {
                if (!value.recalled()) continue;
                long sequence = Objects.requireNonNull(value.mutationSequence());
                entry.setObject(1, value.conversationId());
                entry.setLong(2, sequence);
                entry.addBatch();
                event.setObject(1, value.conversationId());
                event.setLong(2, sequence);
                event.setObject(3, value.messageId());
                event.setObject(4, value.senderAccountId());
                event.addBatch();
            }
            entry.executeBatch();
            event.executeBatch();
        }
    }

    private static void insertDeletionEntriesAndEvents(
            Connection connection, List<PlannedV1DeletionEvent> deletions) throws SQLException {
        try (PreparedStatement entry = connection.prepareStatement("""
                INSERT INTO chat.conversation_entry(
                    conversation_id, conversation_sequence, entry_kind, occurred_at)
                VALUES (?, ?, 'MESSAGES_DELETED', ?) ON CONFLICT DO NOTHING
                """);
                PreparedStatement event = connection.prepareStatement("""
                INSERT INTO chat.messages_deleted_event(
                    conversation_id, conversation_sequence, actor_account_id, source,
                    mode, client_operation_id, command_fingerprint, message_ids,
                    file_ids, cutoff_epoch_ms, deleted_count, operator_name_snapshot)
                VALUES (?, ?, ?, 'V1_IMPORT', ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """);
                PreparedStatement mapping = connection.prepareStatement("""
                INSERT INTO chat.legacy_v1_deletion_event_map(
                    legacy_event_id, legacy_room_id, conversation_id,
                    conversation_sequence)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            for (PlannedV1DeletionEvent value : deletions) {
                entry.setObject(1, value.conversationId());
                entry.setLong(2, value.conversationSequence());
                entry.setObject(3, OffsetDateTime.ofInstant(
                        value.occurredAt(), ZoneOffset.UTC));
                entry.addBatch();
                event.setObject(1, value.conversationId());
                event.setLong(2, value.conversationSequence());
                event.setObject(3, value.actorAccountId());
                event.setString(4, value.mode());
                event.setString(5, value.clientOperationId());
                event.setString(6, value.commandFingerprint());
                event.setString(7, value.messageIdsJson());
                event.setString(8, value.fileIdsJson());
                event.setLong(9, value.cutoffEpochMs());
                event.setInt(10, value.deletedCount());
                event.setString(11, nullToEmpty(value.operatorName()));
                event.addBatch();
                mapping.setLong(1, value.legacyEventId());
                mapping.setLong(2, value.legacyRoomId());
                mapping.setObject(3, value.conversationId());
                mapping.setLong(4, value.conversationSequence());
                mapping.addBatch();
            }
            entry.executeBatch();
            event.executeBatch();
            mapping.executeBatch();
        }
    }

    private static void updateReadCursors(
            Connection connection, List<PlannedV1MemberReadCursor> cursors) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation_member SET last_read_sequence = ?
                WHERE conversation_id = ? AND account_id = ? AND left_at IS NULL
                  AND (last_read_sequence = 0 OR last_read_sequence = ?)
                """)) {
            for (PlannedV1MemberReadCursor value : cursors) {
                statement.setLong(1, value.targetLastReadSequence());
                statement.setObject(2, value.conversationId());
                statement.setObject(3, value.accountId());
                statement.setLong(4, value.targetLastReadSequence());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void updateConversationCursors(
            Connection connection, List<PlannedV1ConversationCursor> cursors) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation
                SET next_sequence = ?, updated_at = transaction_timestamp()
                WHERE id = ? AND (next_sequence = 1 OR next_sequence = ?)
                """)) {
            for (PlannedV1ConversationCursor value : cursors) {
                statement.setLong(1, value.targetNextSequence());
                statement.setObject(2, value.conversationId());
                statement.setLong(3, value.targetNextSequence());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void persistProof(
            Connection connection,
            UUID runId,
            VerifiedV1MessageImportBundle bundle,
            V1MessageTargetImportPlan plan,
            Comparison before) throws SQLException {
        int recalled = (int) plan.messages().stream()
                .filter(PlannedV1HistoricalMessage::recalled).count();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.message_import_run(
                    id, state_fingerprint_sha256, payload_fingerprint_sha256,
                    backup_file_sha256, source_messages, source_recalled_messages,
                    source_deletion_events, source_legacy_devices,
                    source_member_read_cursors, inserted_messages,
                    already_imported_messages, inserted_entries,
                    already_imported_entries, inserted_legacy_devices,
                    already_imported_legacy_devices, updated_read_cursors,
                    already_translated_read_cursors, backup_bytes, backup_created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, runId);
            statement.setString(2, plan.stateFingerprintSha256());
            statement.setString(3, plan.payloadFingerprintSha256());
            statement.setString(4, bundle.backupProof().backupFileSha256());
            statement.setInt(5, plan.messages().size());
            statement.setInt(6, recalled);
            statement.setInt(7, plan.deletionEvents().size());
            statement.setInt(8, plan.legacyDevices().size());
            statement.setInt(9, plan.memberReadCursors().size());
            statement.setInt(10, before.insertMessages());
            statement.setInt(11, before.existingMessages());
            statement.setInt(12, before.insertEntries());
            statement.setInt(13, before.existingEntries());
            statement.setInt(14, before.insertDevices());
            statement.setInt(15, before.existingDevices());
            statement.setInt(16, before.updateReads());
            statement.setInt(17, before.existingReads());
            statement.setLong(18, bundle.backupProof().backupBytes());
            statement.setObject(19, OffsetDateTime.ofInstant(
                    bundle.backupProof().createdAt(), ZoneOffset.UTC));
            if (statement.executeUpdate() != 1) {
                throw new SQLException("message import proof was not persisted");
            }
        }
    }

    private static void lockTarget(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                LOCK TABLE chat.account, chat.device, chat.conversation,
                    chat.conversation_member, chat.message, chat.conversation_entry,
                    chat.message_recall_event, chat.messages_deleted_event,
                    chat.legacy_v1_conversation_map, chat.legacy_v1_message_map,
                    chat.legacy_v1_deletion_event_map, chat.message_import_run
                IN SHARE ROW EXCLUSIVE MODE
                """)) {
            statement.execute();
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
            int missingAuxiliaryRows,
            List<V1MessageTargetIssue> issues) {
        boolean ready() {
            return issues.isEmpty();
        }

        boolean fullyReconciled(V1MessageTargetImportPlan plan) {
            int expectedEntries = plan.messages().size()
                    + (int) plan.messages().stream()
                            .filter(PlannedV1HistoricalMessage::recalled).count()
                    + plan.deletionEvents().size();
            return ready()
                    && insertMessages == 0
                    && existingMessages == plan.messages().size()
                    && insertEntries == 0
                    && existingEntries == expectedEntries
                    && insertDevices == 0
                    && existingDevices == plan.legacyDevices().size()
                    && updateReads == 0
                    && existingReads == plan.memberReadCursors().size()
                    && missingAuxiliaryRows == 0;
        }

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

        V1MessageImportReport appliedReport(V1MessageTargetImportPlan plan, UUID runId) {
            V1MessageImportReport preview = report(plan);
            return new V1MessageImportReport(
                    preview.stateFingerprintSha256(), preview.payloadFingerprintSha256(),
                    preview.sourceMessages(), preview.sourceEntries(),
                    preview.sourceLegacyDevices(), preview.sourceReadCursors(),
                    preview.insertableMessages(), preview.alreadyImportedMessages(),
                    preview.insertableEntries(), preview.alreadyImportedEntries(),
                    preview.insertableLegacyDevices(), preview.alreadyImportedLegacyDevices(),
                    preview.readCursorsToUpdate(), preview.alreadyTranslatedReadCursors(),
                    List.of(), true, true, runId);
        }
    }
}
