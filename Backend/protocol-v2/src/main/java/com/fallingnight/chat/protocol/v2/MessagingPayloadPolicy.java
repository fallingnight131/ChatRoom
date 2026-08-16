package com.fallingnight.chat.protocol.v2;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Structural bounds for authenticated messaging payloads before application mapping. */
public final class MessagingPayloadPolicy {
    public static final int MAX_CONTENT_BYTES = 1_000_000;
    public static final int MAX_TEXT_UTF8_BYTES = 65_536;
    public static final int MAX_HISTORY_LIMIT = 100;
    public static final int MAX_DELETION_TARGETS = 1_000;
    public static final int MAX_CONTENT_REVISIONS = 100;
    public static final int MAX_MENTION_SPANS = 20;
    public static final int MAX_DISTINCT_MENTION_TARGETS = 10;
    public static final int MAX_SEARCH_QUERY_UTF8_BYTES = 128;
    public static final int MAX_SEARCH_LIMIT = 50;

    private MessagingPayloadPolicy() {}

    public static List<String> violations(SubmitMessage command, String clientMessageId) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", command.getConversationId(), violations);
        if (command.getContent().size() > MAX_CONTENT_BYTES) {
            violations.add("content exceeds messaging limit");
        }
        validateContent(command.getContentType(), command.getContent(), violations);
        validateMentions(command.getContent(), command.getMentionsList(), violations);
        requireIdentifier("clientMessageId", clientMessageId, true, violations);
        return List.copyOf(violations);
    }

    public static List<String> violations(
            SubmitReplyMessage command, String clientMessageId) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", command.getConversationId(), violations);
        requireUuid("targetMessageId", command.getTargetMessageId(), violations);
        if (command.getContent().size() > MAX_CONTENT_BYTES) {
            violations.add("content exceeds messaging limit");
        }
        validateContent(command.getContentType(), command.getContent(), violations);
        validateMentions(command.getContent(), command.getMentionsList(), violations);
        requireIdentifier("clientMessageId", clientMessageId, true, violations);
        return List.copyOf(violations);
    }

    public static List<String> violations(ForwardMessage command, String clientMessageId) {
        List<String> violations = new ArrayList<>();
        requireUuid("sourceConversationId", command.getSourceConversationId(), violations);
        requireUuid("sourceMessageId", command.getSourceMessageId(), violations);
        requireUuid("targetConversationId", command.getTargetConversationId(), violations);
        if (command.getExpectedSourceContentRevision() > MAX_CONTENT_REVISIONS) {
            violations.add("expectedSourceContentRevision exceeds edit limit");
        }
        requireIdentifier("clientMessageId", clientMessageId, true, violations);
        return List.copyOf(violations);
    }

    public static List<String> violations(ReadMessageHistory command) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", command.getConversationId(), violations);
        if (command.getAfterSequence() < 0) {
            violations.add("afterSequence exceeds signed server range");
        }
        if (command.getLimit() < 1 || command.getLimit() > MAX_HISTORY_LIMIT) {
            violations.add("limit must be in 1..100");
        }
        return List.copyOf(violations);
    }

    public static List<String> violations(SearchConversationMessages command) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", command.getConversationId(), violations);
        validateSearchQuery(command.getLiteralQuery(), violations);
        if (command.getBeforeSequence() < 0) {
            violations.add("beforeSequence exceeds signed server range");
        }
        if (command.getLimit() < 1 || command.getLimit() > MAX_SEARCH_LIMIT) {
            violations.add("limit must be in 1..50");
        }
        return List.copyOf(violations);
    }

    public static List<String> violations(SetMessageReaction command) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", command.getConversationId(), violations);
        requireUuid("messageId", command.getMessageId(), violations);
        requireIdentifier("clientOperationId", command.getClientOperationId(), true, violations);
        if (!supportedReaction(command.getReaction())) {
            violations.add("reaction is unsupported");
        }
        return List.copyOf(violations);
    }

    public static List<String> violations(SetMessagePin command) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", command.getConversationId(), violations);
        requireUuid("messageId", command.getMessageId(), violations);
        requireIdentifier("clientOperationId", command.getClientOperationId(), true, violations);
        return List.copyOf(violations);
    }

    public static List<String> violations(EditMessage command) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", command.getConversationId(), violations);
        requireUuid("messageId", command.getMessageId(), violations);
        requireIdentifier("clientOperationId", command.getClientOperationId(), true, violations);
        if (command.getExpectedRevision() > MAX_CONTENT_REVISIONS) {
            violations.add("expectedRevision exceeds edit limit");
        }
        validateContent(command.getContentType(), command.getContent(), violations);
        validateMentions(command.getContent(), command.getMentionsList(), violations);
        return List.copyOf(violations);
    }

    public static void requireValid(SubmitMessage command, String clientMessageId) {
        requireNone(violations(command, clientMessageId));
    }

    public static void requireValid(SubmitReplyMessage command, String clientMessageId) {
        requireNone(violations(command, clientMessageId));
    }

    public static void requireValid(ForwardMessage command, String clientMessageId) {
        requireNone(violations(command, clientMessageId));
    }

    public static void requireValid(ReadMessageHistory command) {
        requireNone(violations(command));
    }

    public static void requireValid(SearchConversationMessages command) {
        requireNone(violations(command));
    }

    private static void validateSearchQuery(String query, List<String> violations) {
        if (!query.equals(query.strip())) {
            violations.add("literalQuery must be stripped");
            return;
        }
        try {
            int bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(query)).remaining();
            if (bytes < 1 || bytes > MAX_SEARCH_QUERY_UTF8_BYTES) {
                violations.add("literalQuery must contain 1..128 UTF-8 bytes");
            }
        } catch (CharacterCodingException exception) {
            violations.add("literalQuery must be valid UTF-8");
        }
    }

    public static void requireValid(SetMessageReaction command) {
        requireNone(violations(command));
    }

    public static void requireValid(SetMessagePin command) {
        requireNone(violations(command));
    }

    public static void requireValid(EditMessage command) {
        requireNone(violations(command));
    }

    public static void requireValid(MessageEditApplied response) {
        List<String> violations = new ArrayList<>();
        validateEditIdentity(response.getConversationId(), response.getMessageId(),
                response.getActorAccountId(), response.getClientOperationId(), violations);
        validateContent(response.getContentType(), response.getContent(), violations);
        validateMentions(response.getContent(), response.getMentionsList(), violations);
        if (response.getContentRevision() > MAX_CONTENT_REVISIONS
                || (response.getChanged() && response.getContentRevision() == 0)
                || response.getOccurredAtEpochMs() <= 0
                || response.getChanged() != (response.getConversationSequence() > 0)) {
            violations.add("edit result bounds are invalid");
        }
        requireNone(violations);
    }

    public static void requireValid(MessageEditedRecord event) {
        List<String> violations = new ArrayList<>();
        validateEdit(event, violations);
        requireNone(violations);
    }

    public static void requireValid(MessagePinApplied response) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", response.getConversationId(), violations);
        requireUuid("messageId", response.getMessageId(), violations);
        requireUuid("actorAccountId", response.getActorAccountId(), violations);
        requireIdentifier("clientOperationId", response.getClientOperationId(), true, violations);
        if (response.getOccurredAtEpochMs() <= 0
                || response.getChanged() != (response.getConversationSequence() > 0)) {
            violations.add("pin result bounds are invalid");
        }
        requireNone(violations);
    }

    public static void requireValid(MessagePinChangedRecord event) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", event.getConversationId(), violations);
        requireUuid("messageId", event.getMessageId(), violations);
        requireUuid("actorAccountId", event.getActorAccountId(), violations);
        requireIdentifier("clientOperationId", event.getClientOperationId(), true, violations);
        if (event.getConversationSequence() <= 0 || event.getOccurredAtEpochMs() <= 0) {
            violations.add("pin event bounds are invalid");
        }
        requireNone(violations);
    }

    public static void requireValid(MessageReactionApplied response) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", response.getConversationId(), violations);
        requireUuid("messageId", response.getMessageId(), violations);
        requireUuid("actorAccountId", response.getActorAccountId(), violations);
        requireIdentifier("clientOperationId",
                response.getClientOperationId(), true, violations);
        if (!supportedReaction(response.getReaction())
                || response.getOccurredAtEpochMs() <= 0
                || response.getChanged() != (response.getConversationSequence() > 0)) {
            violations.add("reaction result bounds are invalid");
        }
        requireNone(violations);
    }

    public static void requireValid(MessageReactionChangedRecord event) {
        List<String> violations = new ArrayList<>();
        validateReaction(event, violations);
        requireNone(violations);
    }

    public static void requireValid(MessageAccepted response) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", response.getConversationId(), violations);
        requireUuid("messageId", response.getMessageId(), violations);
        if (response.getConversationSequence() <= 0) {
            violations.add("conversationSequence must be positive");
        }
        if (response.getAcceptedAtEpochMs() <= 0) {
            violations.add("acceptedAtEpochMs must be positive");
        }
        requireNone(violations);
    }

    public static void requireValid(MessageRecord event) {
        List<String> violations = new ArrayList<>();
        validateMessageRecord(event, violations);
        requireNone(violations);
    }

    public static void requireValid(MessageHistoryPage page) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", page.getConversationId(), violations);
        if (page.getMessagesCount() > MAX_HISTORY_LIMIT
                || page.getEntriesCount() > MAX_HISTORY_LIMIT
                || page.getNextSequence() < 0
                || page.getLatestSequence() < 0) {
            violations.add("history page bounds are invalid");
        }
        long previous = 0;
        for (MessageRecord message : page.getMessagesList()) {
            validateMessageRecord(message, violations);
            if (!page.getConversationId().equals(message.getConversationId())
                    || message.getConversationSequence() <= previous) {
                violations.add("history message is invalid or out of order");
            }
            previous = message.getConversationSequence();
        }
        long previousEntry = 0;
        for (ConversationEntryRecord entry : page.getEntriesList()) {
            validateConversationEntry(entry, page.getConversationId(), violations);
            if (entry.getConversationSequence() <= previousEntry) {
                violations.add("history entry is out of order");
            }
            previousEntry = entry.getConversationSequence();
        }
        long last = page.getEntriesCount() == 0 ? previous : previousEntry;
        if (last != 0 && page.getNextSequence() < last) {
            violations.add("nextSequence must not trail the last visible history sequence");
        }
        requireNone(violations);
    }

    private static void validateConversationEntry(
            ConversationEntryRecord entry,
            String pageConversationId,
            List<String> violations) {
        requireUuid("entry.conversationId", entry.getConversationId(), violations);
        if (!pageConversationId.equals(entry.getConversationId())
                || entry.getConversationSequence() <= 0) {
            violations.add("history entry identity is invalid");
        }
        switch (entry.getDetailCase()) {
            case MESSAGE -> {
                validateMessageRecord(entry.getMessage(), violations);
                if (!entry.getConversationId().equals(entry.getMessage().getConversationId())
                        || entry.getConversationSequence()
                                != entry.getMessage().getConversationSequence()) {
                    violations.add("message entry detail identity differs");
                }
            }
            case RECALL -> {
                MessageRecalledRecord recall = entry.getRecall();
                requireUuid("recall.conversationId", recall.getConversationId(), violations);
                requireUuid("recall.messageId", recall.getMessageId(), violations);
                requireUuid("recall.actorAccountId", recall.getActorAccountId(), violations);
                requireIdentifier("recall.source", recall.getSource(), true, violations);
                if (!entry.getConversationId().equals(recall.getConversationId())
                        || entry.getConversationSequence() != recall.getConversationSequence()
                        || !supportedSource(recall.getSource())
                        || recall.getOccurredAtEpochMs() < 0) {
                    violations.add("recall entry detail is invalid");
                }
            }
            case DELETION -> {
                MessagesDeletedRecord deletion = entry.getDeletion();
                requireUuid("deletion.conversationId", deletion.getConversationId(), violations);
                requireUuid("deletion.actorAccountId", deletion.getActorAccountId(), violations);
                requireIdentifier("deletion.source", deletion.getSource(), true, violations);
                requireIdentifier("deletion.mode", deletion.getMode(), true, violations);
                requireIdentifier("deletion.clientOperationId",
                        deletion.getClientOperationId(), true, violations);
                deletion.getMessageIdsList().forEach(value ->
                        requireUuid("deletion.messageId", value, violations));
                if (!entry.getConversationId().equals(deletion.getConversationId())
                        || entry.getConversationSequence()
                                != deletion.getConversationSequence()
                        || deletion.getCutoffEpochMs() < 0
                        || deletion.getOccurredAtEpochMs() <= 0
                        || !supportedSource(deletion.getSource())
                        || !java.util.Set.of("selected", "all", "before", "after")
                                .contains(deletion.getMode())
                        || deletion.getMessageIdsCount() > MAX_DELETION_TARGETS
                        || deletion.getOperatorNameSnapshot().codePointCount(
                                0, deletion.getOperatorNameSnapshot().length()) > 100) {
                    violations.add("deletion entry detail is invalid");
                }
            }
            case REACTION -> {
                MessageReactionChangedRecord reaction = entry.getReaction();
                validateReaction(reaction, violations);
                if (!entry.getConversationId().equals(reaction.getConversationId())
                        || entry.getConversationSequence()
                                != reaction.getConversationSequence()) {
                    violations.add("reaction entry detail identity differs");
                }
            }
            case PIN -> {
                MessagePinChangedRecord pin = entry.getPin();
                List<String> pinViolations = new ArrayList<>();
                requireUuid("pin.conversationId", pin.getConversationId(), pinViolations);
                requireUuid("pin.messageId", pin.getMessageId(), pinViolations);
                requireUuid("pin.actorAccountId", pin.getActorAccountId(), pinViolations);
                requireIdentifier("pin.clientOperationId",
                        pin.getClientOperationId(), true, pinViolations);
                if (!entry.getConversationId().equals(pin.getConversationId())
                        || entry.getConversationSequence() != pin.getConversationSequence()
                        || pin.getConversationSequence() <= 0
                        || pin.getOccurredAtEpochMs() <= 0) {
                    pinViolations.add("pin entry detail is invalid");
                }
                violations.addAll(pinViolations);
            }
            case EDIT -> {
                MessageEditedRecord edit = entry.getEdit();
                validateEdit(edit, violations);
                if (!entry.getConversationId().equals(edit.getConversationId())
                        || entry.getConversationSequence() != edit.getConversationSequence()) {
                    violations.add("edit entry detail identity differs");
                }
            }
            case DETAIL_NOT_SET -> violations.add("history entry detail is required");
        }
    }

    private static void validateReaction(
            MessageReactionChangedRecord reaction, List<String> violations) {
        requireUuid("reaction.conversationId", reaction.getConversationId(), violations);
        requireUuid("reaction.messageId", reaction.getMessageId(), violations);
        requireUuid("reaction.actorAccountId", reaction.getActorAccountId(), violations);
        requireIdentifier("reaction.clientOperationId",
                reaction.getClientOperationId(), true, violations);
        if (reaction.getConversationSequence() <= 0
                || reaction.getOccurredAtEpochMs() <= 0
                || !supportedReaction(reaction.getReaction())) {
            violations.add("reaction entry detail is invalid");
        }
    }

    private static void validateEdit(MessageEditedRecord edit, List<String> violations) {
        validateEditIdentity(edit.getConversationId(), edit.getMessageId(),
                edit.getActorAccountId(), edit.getClientOperationId(), violations);
        validateContent(edit.getContentType(), edit.getContent(), violations);
        validateMentions(edit.getContent(), edit.getMentionsList(), violations);
        if (edit.getConversationSequence() <= 0 || edit.getContentRevision() < 1
                || edit.getContentRevision() > MAX_CONTENT_REVISIONS
                || edit.getOccurredAtEpochMs() <= 0) {
            violations.add("edit entry detail is invalid");
        }
    }

    private static void validateEditIdentity(
            String conversationId, String messageId, String actorAccountId,
            String clientOperationId, List<String> violations) {
        requireUuid("edit.conversationId", conversationId, violations);
        requireUuid("edit.messageId", messageId, violations);
        requireUuid("edit.actorAccountId", actorAccountId, violations);
        requireIdentifier("edit.clientOperationId", clientOperationId, true, violations);
    }

    private static boolean supportedReaction(MessageReactionKind reaction) {
        return switch (reaction) {
            case MESSAGE_REACTION_KIND_LIKE, MESSAGE_REACTION_KIND_LOVE,
                    MESSAGE_REACTION_KIND_LAUGH, MESSAGE_REACTION_KIND_SURPRISED,
                    MESSAGE_REACTION_KIND_SAD, MESSAGE_REACTION_KIND_ANGRY -> true;
            case MESSAGE_REACTION_KIND_UNSPECIFIED, UNRECOGNIZED -> false;
        };
    }

    private static boolean supportedSource(String value) {
        return "V2".equals(value) || "V1_IMPORT".equals(value);
    }

    private static void validateMessageRecord(MessageRecord message, List<String> violations) {
        requireUuid("conversationId", message.getConversationId(), violations);
        requireUuid("messageId", message.getMessageId(), violations);
        requireUuid("senderAccountId", message.getSenderAccountId(), violations);
        requireUuid("senderDeviceId", message.getSenderDeviceId(), violations);
        requireIdentifier("clientMessageId", message.getClientMessageId(), true, violations);
        if (message.getConversationSequence() <= 0
                || message.getContent().size() > MAX_CONTENT_BYTES
                || message.getAcceptedAtEpochMs() <= 0) {
            violations.add("message record bounds are invalid");
        }
        validateContent(message.getContentType(), message.getContent(), violations);
        validateMentions(message.getContent(), message.getMentionsList(), violations);
        if (message.getContentRevision() > MAX_CONTENT_REVISIONS
                || (message.getContentRevision() == 0) != (message.getEditedAtEpochMs() == 0)) {
            violations.add("message edit metadata is invalid");
        }
        if (message.hasReply()) {
            MessageReplyReference reply = message.getReply();
            requireUuid("reply.targetMessageId", reply.getTargetMessageId(), violations);
            requireUuid("reply.targetSenderAccountId",
                    reply.getTargetSenderAccountId(), violations);
            if (reply.getTargetConversationSequence() <= 0
                    || reply.getTargetConversationSequence()
                            >= message.getConversationSequence()) {
                violations.add("reply target sequence must precede the reply message");
            }
        }
    }

    private static void requireUuid(String field, String value, List<String> violations) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                violations.add(field + " must be a canonical UUID");
            }
        } catch (IllegalArgumentException exception) {
            violations.add(field + " must be a canonical UUID");
        }
    }

    private static void requireIdentifier(
            String field, String value, boolean required, List<String> violations) {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if ((required && value.isBlank()) || bytes > EnvelopePolicy.MAX_IDENTIFIER_BYTES) {
            violations.add(field + " must contain 1..128 UTF-8 bytes");
        }
    }

    private static void requireNone(List<String> violations) {
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", violations));
        }
    }

    private static void validateContent(
            int contentType,
            com.google.protobuf.ByteString content,
            List<String> violations) {
        if (contentType != MessageContentType.MESSAGE_CONTENT_TYPE_TEXT_UTF8_VALUE
                || content.isEmpty()
                || content.size() > MAX_TEXT_UTF8_BYTES
                || !content.isValidUtf8()) {
            violations.add("contentType/content is unsupported or invalid");
        }
    }

    private static void validateMentions(
            com.google.protobuf.ByteString content,
            List<MessageMention> mentions,
            List<String> violations) {
        if (mentions.size() > MAX_MENTION_SPANS) {
            violations.add("mentions exceed span limit");
        }
        byte[] body = content.toByteArray();
        long previousEnd = 0;
        HashSet<String> targets = new HashSet<>();
        for (MessageMention mention : mentions) {
            requireUuid("mention.targetAccountId", mention.getTargetAccountId(), violations);
            targets.add(mention.getTargetAccountId());
            long start = Integer.toUnsignedLong(mention.getStartUtf8Byte());
            long length = Integer.toUnsignedLong(mention.getLengthUtf8Bytes());
            long end = start + length;
            if (length == 0 || start < previousEnd || end > body.length
                    || !isUtf8Boundary(body, start) || !isUtf8Boundary(body, end)
                    || start >= body.length || body[(int) start] != '@') {
                violations.add("mention span is invalid, overlapping, or unordered");
            }
            previousEnd = end;
        }
        if (targets.size() > MAX_DISTINCT_MENTION_TARGETS) {
            violations.add("mentions exceed distinct target limit");
        }
    }

    private static boolean isUtf8Boundary(byte[] value, long index) {
        return index >= 0 && index <= value.length
                && (index == 0 || index == value.length
                    || (value[(int) index] & 0xc0) != 0x80);
    }
}
