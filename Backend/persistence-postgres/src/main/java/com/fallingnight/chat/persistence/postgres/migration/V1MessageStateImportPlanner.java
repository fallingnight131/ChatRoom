package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure validation and conservative cursor translation for V1 message state. */
public final class V1MessageStateImportPlanner {
    public V1MessageStateImportPlan plan(V1MessageStateSourceSnapshot source) {
        List<V1ConversationWatermarkRow> sourceWatermarks = sortedWatermarks(source.watermarks());
        List<V1MessageCursorRow> sourceMessages = sortedMessages(source.messages());
        List<V1RoomDeletionCursorRow> sourceDeletionEvents =
                sortedDeletionEvents(source.roomDeletionEvents());
        String fingerprint = fingerprint(
                source.conversationPlan(), sourceWatermarks, sourceMessages, sourceDeletionEvents);
        List<V1MessageStateImportIssue> issues = new ArrayList<>();
        if (!source.conversationPlan().readyToCompareWithTarget()) {
            issues.add(issue(null, 0, "CONVERSATION_PLAN_NOT_READY",
                    "conversation metadata must be valid before message state"));
            return new V1MessageStateImportPlan(
                    fingerprint, sourceMessages.size(), sourceDeletionEvents.size(),
                    sourceMessages, sourceDeletionEvents, List.of(), List.of(), issues);
        }

        Map<LegacyKey, PlannedV1Conversation> conversations = new HashMap<>();
        Map<UUID, LegacyKey> legacyByTarget = new HashMap<>();
        for (PlannedV1Conversation conversation : source.conversationPlan().conversations()) {
            LegacyKey key = new LegacyKey(conversation.legacyKind(), conversation.legacyId());
            conversations.put(key, conversation);
            legacyByTarget.put(conversation.conversationId(), key);
        }

        Map<LegacyKey, Long> watermarks = validateWatermarks(
                sourceWatermarks, conversations.keySet(), issues);
        Map<LegacyKey, List<V1MessageCursorRow>> messages = validateMessages(
                sourceMessages, conversations.keySet(), watermarks, issues);
        validateMessageSenders(messages, conversations, source.conversationPlan(), issues);
        validateDeletionEvents(sourceDeletionEvents, conversations.keySet(),
                watermarks, messages, issues);

        List<PlannedV1ConversationCursor> cursors = new ArrayList<>();
        for (Map.Entry<LegacyKey, PlannedV1Conversation> entry : conversations.entrySet()) {
            Long watermark = watermarks.get(entry.getKey());
            if (watermark != null && watermark < Long.MAX_VALUE) {
                cursors.add(new PlannedV1ConversationCursor(
                        entry.getValue().conversationId(), watermark, watermark + 1));
            } else if (watermark != null) {
                issues.add(issue(entry.getKey(), "WATERMARK_EXHAUSTED",
                        "legacy high watermark cannot be incremented in V2"));
            }
        }
        cursors.sort(Comparator.comparing(value -> value.conversationId().toString()));

        List<PlannedV1MemberReadCursor> readCursors = new ArrayList<>();
        for (PlannedV1ConversationMember member : source.conversationPlan().memberships()) {
            LegacyKey key = legacyByTarget.get(member.conversationId());
            long translated = messages.getOrDefault(key, List.of()).stream()
                    .filter(row -> row.legacyMessageId() <= member.legacyLastReadMessageId())
                    .mapToLong(V1MessageCursorRow::creationSequence)
                    .max()
                    .orElse(0);
            readCursors.add(new PlannedV1MemberReadCursor(
                    member.conversationId(), member.accountId(),
                    member.legacyLastReadMessageId(), translated));
        }
        readCursors.sort(Comparator
                .comparing((PlannedV1MemberReadCursor value) ->
                        value.conversationId().toString())
                .thenComparing(value -> value.accountId().toString()));
        return new V1MessageStateImportPlan(
                fingerprint, sourceMessages.size(), sourceDeletionEvents.size(),
                sourceMessages, sourceDeletionEvents, cursors, readCursors, issues);
    }

    private static void validateMessageSenders(
            Map<LegacyKey, List<V1MessageCursorRow>> messages,
            Map<LegacyKey, PlannedV1Conversation> conversations,
            V1ConversationImportPlan conversationPlan,
            List<V1MessageStateImportIssue> issues) {
        Map<UUID, Set<UUID>> members = new HashMap<>();
        for (PlannedV1ConversationMember member : conversationPlan.memberships()) {
            members.computeIfAbsent(member.conversationId(), ignored -> new HashSet<>())
                    .add(member.accountId());
        }
        for (Map.Entry<LegacyKey, List<V1MessageCursorRow>> entry : messages.entrySet()) {
            PlannedV1Conversation conversation = conversations.get(entry.getKey());
            if (conversation == null) {
                continue;
            }
            Set<UUID> conversationMembers = members.getOrDefault(
                    conversation.conversationId(), Set.of());
            for (V1MessageCursorRow message : entry.getValue()) {
                UUID sender = V1IdentityImportPlanner.deterministicUserId(
                        message.legacySenderUserId());
                if (!conversationMembers.contains(sender)) {
                    issues.add(issue(entry.getKey(), "MESSAGE_SENDER_NOT_MEMBER",
                            "message sender must reference an imported conversation member"));
                }
            }
        }
    }

    private static List<V1ConversationWatermarkRow> sortedWatermarks(
            List<V1ConversationWatermarkRow> source) {
        List<V1ConversationWatermarkRow> result = new ArrayList<>(source);
        result.sort(Comparator.comparing(V1ConversationWatermarkRow::legacyKind)
                .thenComparingLong(V1ConversationWatermarkRow::legacyConversationId));
        return result;
    }

    private static List<V1MessageCursorRow> sortedMessages(List<V1MessageCursorRow> source) {
        List<V1MessageCursorRow> result = new ArrayList<>(source);
        result.sort(Comparator.comparing(V1MessageCursorRow::legacyKind)
                .thenComparingLong(V1MessageCursorRow::legacyMessageId)
                .thenComparingLong(V1MessageCursorRow::legacyConversationId));
        return result;
    }

    private static List<V1RoomDeletionCursorRow> sortedDeletionEvents(
            List<V1RoomDeletionCursorRow> source) {
        List<V1RoomDeletionCursorRow> result = new ArrayList<>(source);
        result.sort(Comparator.comparingLong(V1RoomDeletionCursorRow::legacyEventId)
                .thenComparingLong(V1RoomDeletionCursorRow::legacyRoomId));
        return result;
    }

    private static String fingerprint(
            V1ConversationImportPlan conversations,
            List<V1ConversationWatermarkRow> watermarks,
            List<V1MessageCursorRow> messages,
            List<V1RoomDeletionCursorRow> deletionEvents) {
        MessageDigest digest = digest();
        try (DataOutputStream data = new DataOutputStream(
                new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            data.writeUTF(conversations.sourceFingerprintSha256());
            data.writeInt(watermarks.size());
            for (V1ConversationWatermarkRow row : watermarks) {
                data.writeUTF(row.legacyKind().name());
                data.writeLong(row.legacyConversationId());
                data.writeLong(row.lastSequence());
            }
            data.writeInt(messages.size());
            for (V1MessageCursorRow row : messages) {
                data.writeUTF(row.legacyKind().name());
                data.writeLong(row.legacyConversationId());
                data.writeLong(row.legacyMessageId());
                data.writeLong(row.legacySenderUserId());
                data.writeLong(row.creationSequence());
                data.writeBoolean(row.mutationSequence() != null);
                if (row.mutationSequence() != null) {
                    data.writeLong(row.mutationSequence());
                }
                data.writeBoolean(row.recalled());
                data.writeUTF(row.createdAt() == null ? "" : row.createdAt().toString());
            }
            data.writeInt(deletionEvents.size());
            for (V1RoomDeletionCursorRow row : deletionEvents) {
                data.writeLong(row.legacyEventId());
                data.writeLong(row.legacyRoomId());
                data.writeLong(row.legacyOperatorUserId());
                data.writeLong(row.sequence());
                data.writeUTF(row.createdAt() == null ? "" : row.createdAt().toString());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalStateException("in-memory message state fingerprint failed", exception);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Map<LegacyKey, Long> validateWatermarks(
            List<V1ConversationWatermarkRow> rows,
            Set<LegacyKey> conversations,
            List<V1MessageStateImportIssue> issues) {
        Map<LegacyKey, Long> result = new HashMap<>();
        for (V1ConversationWatermarkRow row : rows) {
            LegacyKey key = new LegacyKey(row.legacyKind(), row.legacyConversationId());
            if (!conversations.contains(key)) {
                issues.add(issue(key, "UNKNOWN_WATERMARK_CONVERSATION",
                        "watermark must reference an imported conversation"));
            }
            if (row.lastSequence() < 0) {
                issues.add(issue(key, "INVALID_WATERMARK", "high watermark must be nonnegative"));
            }
            if (result.putIfAbsent(key, row.lastSequence()) != null) {
                issues.add(issue(key, "DUPLICATE_WATERMARK",
                        "conversation must have one high watermark"));
            }
        }
        for (LegacyKey key : conversations) {
            if (!result.containsKey(key)) {
                issues.add(issue(key, "MISSING_WATERMARK",
                        "conversation must have a migrated high watermark"));
            }
        }
        return result;
    }

    private static Map<LegacyKey, List<V1MessageCursorRow>> validateMessages(
            List<V1MessageCursorRow> rows,
            Set<LegacyKey> conversations,
            Map<LegacyKey, Long> watermarks,
            List<V1MessageStateImportIssue> issues) {
        Map<LegacyKey, List<V1MessageCursorRow>> result = new HashMap<>();
        Map<LegacyKey, Set<Long>> usedSequences = new HashMap<>();
        Set<MessageKey> messageIds = new HashSet<>();
        for (V1MessageCursorRow row : rows) {
            LegacyKey key = new LegacyKey(row.legacyKind(), row.legacyConversationId());
            if (!conversations.contains(key)) {
                issues.add(issue(key, "UNKNOWN_MESSAGE_CONVERSATION",
                        "message must reference an imported conversation"));
            }
            if (row.legacyMessageId() <= 0
                    || !messageIds.add(new MessageKey(row.legacyKind(), row.legacyMessageId()))) {
                issues.add(issue(key, "INVALID_OR_DUPLICATE_MESSAGE_ID",
                        "legacy message id must be positive and unique in its table"));
            }
            validateSequence(key, row.creationSequence(), watermarks, usedSequences,
                    "INVALID_MESSAGE_SEQUENCE", issues);
            if (row.mutationSequence() != null) {
                validateSequence(key, row.mutationSequence(), watermarks, usedSequences,
                        "INVALID_MUTATION_SEQUENCE", issues);
                if (row.mutationSequence() <= row.creationSequence()) {
                    issues.add(issue(key, "MUTATION_NOT_AFTER_CREATION",
                            "mutation sequence must follow message creation"));
                }
            }
            if (row.recalled() != (row.mutationSequence() != null)) {
                issues.add(issue(key, "INCONSISTENT_RECALL_STATE",
                        "recalled state and mutation sequence must agree"));
            }
            if (row.legacySenderUserId() <= 0) {
                issues.add(issue(key, "INVALID_MESSAGE_SENDER",
                        "legacy message sender must be positive"));
            }
            if (row.createdAt() == null) {
                issues.add(issue(key, "INVALID_MESSAGE_CREATED_AT",
                        "message creation timestamp is required"));
            }
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        result.values().forEach(values -> values.sort(
                Comparator.comparingLong(V1MessageCursorRow::legacyMessageId)));
        return result;
    }

    private static void validateDeletionEvents(
            List<V1RoomDeletionCursorRow> rows,
            Set<LegacyKey> conversations,
            Map<LegacyKey, Long> watermarks,
            Map<LegacyKey, List<V1MessageCursorRow>> messages,
            List<V1MessageStateImportIssue> issues) {
        Map<LegacyKey, Set<Long>> usedSequences = new HashMap<>();
        for (Map.Entry<LegacyKey, List<V1MessageCursorRow>> entry : messages.entrySet()) {
            Set<Long> used = usedSequences.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>());
            for (V1MessageCursorRow message : entry.getValue()) {
                used.add(message.creationSequence());
                if (message.mutationSequence() != null) {
                    used.add(message.mutationSequence());
                }
            }
        }
        Set<Long> eventIds = new HashSet<>();
        for (V1RoomDeletionCursorRow row : rows) {
            LegacyKey key = new LegacyKey(LegacyV1ConversationKind.ROOM, row.legacyRoomId());
            if (!conversations.contains(key)) {
                issues.add(issue(key, "UNKNOWN_DELETION_CONVERSATION",
                        "deletion event must reference an imported room"));
            }
            if (row.legacyEventId() <= 0 || !eventIds.add(row.legacyEventId())) {
                issues.add(issue(key, "INVALID_OR_DUPLICATE_DELETION_ID",
                        "legacy deletion event id must be positive and unique"));
            }
            validateSequence(key, row.sequence(), watermarks, usedSequences,
                    "INVALID_DELETION_SEQUENCE", issues);
            if (row.legacyOperatorUserId() <= 0 || row.createdAt() == null) {
                issues.add(issue(key, "INVALID_DELETION_METADATA",
                        "deletion event operator and timestamp are required"));
            }
        }
    }

    private static void validateSequence(
            LegacyKey key,
            long sequence,
            Map<LegacyKey, Long> watermarks,
            Map<LegacyKey, Set<Long>> usedSequences,
            String invalidCode,
            List<V1MessageStateImportIssue> issues) {
        Long watermark = watermarks.get(key);
        if (sequence <= 0 || watermark == null || sequence > watermark) {
            issues.add(issue(key, invalidCode,
                    "allocated sequence must be positive and within the high watermark"));
        }
        if (!usedSequences.computeIfAbsent(key, ignored -> new HashSet<>()).add(sequence)) {
            issues.add(issue(key, "DUPLICATE_CONVERSATION_SEQUENCE",
                    "creation, mutation, and deletion sequences must not collide"));
        }
    }

    private static V1MessageStateImportIssue issue(
            LegacyKey key, String code, String message) {
        return issue(key.kind(), key.id(), code, message);
    }

    private static V1MessageStateImportIssue issue(
            LegacyV1ConversationKind kind, long id, String code, String message) {
        return new V1MessageStateImportIssue(kind, id, code, message);
    }

    private record LegacyKey(LegacyV1ConversationKind kind, long id) {}
    private record MessageKey(LegacyV1ConversationKind kind, long id) {}
}
