package com.fallingnight.chat.protocol.v2;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Structural bounds for authenticated messaging payloads before application mapping. */
public final class MessagingPayloadPolicy {
    public static final int MAX_CONTENT_BYTES = 1_000_000;
    public static final int MAX_TEXT_UTF8_BYTES = 65_536;
    public static final int MAX_HISTORY_LIMIT = 100;
    public static final int MAX_DELETION_TARGETS = 1_000;

    private MessagingPayloadPolicy() {}

    public static List<String> violations(SubmitMessage command, String clientMessageId) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", command.getConversationId(), violations);
        if (command.getContent().size() > MAX_CONTENT_BYTES) {
            violations.add("content exceeds messaging limit");
        }
        validateContent(command.getContentType(), command.getContent(), violations);
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

    public static void requireValid(SubmitMessage command, String clientMessageId) {
        requireNone(violations(command, clientMessageId));
    }

    public static void requireValid(SubmitReplyMessage command, String clientMessageId) {
        requireNone(violations(command, clientMessageId));
    }

    public static void requireValid(ReadMessageHistory command) {
        requireNone(violations(command));
    }

    public static void requireValid(SetMessageReaction command) {
        requireNone(violations(command));
    }

    public static void requireValid(SetMessagePin command) {
        requireNone(violations(command));
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
        if (last != 0 && page.getNextSequence() != last) {
            violations.add("nextSequence must equal the last history sequence");
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
}
