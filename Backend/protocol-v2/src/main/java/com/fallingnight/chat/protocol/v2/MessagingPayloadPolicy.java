package com.fallingnight.chat.protocol.v2;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Structural bounds for authenticated messaging payloads before application mapping. */
public final class MessagingPayloadPolicy {
    public static final int MAX_CONTENT_BYTES = 1_000_000;
    public static final int MAX_HISTORY_LIMIT = 100;

    private MessagingPayloadPolicy() {}

    public static List<String> violations(SubmitMessage command, String clientMessageId) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", command.getConversationId(), violations);
        if (command.getContentType() == 0) {
            violations.add("contentType is required");
        }
        if (command.getContent().size() > MAX_CONTENT_BYTES) {
            violations.add("content exceeds messaging limit");
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

    public static void requireValid(SubmitMessage command, String clientMessageId) {
        requireNone(violations(command, clientMessageId));
    }

    public static void requireValid(ReadMessageHistory command) {
        requireNone(violations(command));
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

    public static void requireValid(MessageHistoryPage page) {
        List<String> violations = new ArrayList<>();
        requireUuid("conversationId", page.getConversationId(), violations);
        if (page.getMessagesCount() > MAX_HISTORY_LIMIT
                || page.getNextSequence() < 0
                || page.getLatestSequence() < 0) {
            violations.add("history page bounds are invalid");
        }
        long previous = 0;
        for (MessageRecord message : page.getMessagesList()) {
            requireUuid("messageId", message.getMessageId(), violations);
            requireUuid("senderAccountId", message.getSenderAccountId(), violations);
            requireUuid("senderDeviceId", message.getSenderDeviceId(), violations);
            requireIdentifier("clientMessageId", message.getClientMessageId(), true, violations);
            if (!page.getConversationId().equals(message.getConversationId())
                    || message.getConversationSequence() <= previous
                    || message.getContentType() == 0
                    || message.getContent().size() > MAX_CONTENT_BYTES
                    || message.getAcceptedAtEpochMs() <= 0) {
                violations.add("history message is invalid or out of order");
            }
            previous = message.getConversationSequence();
        }
        if (!page.getMessagesList().isEmpty()
                && page.getNextSequence() != previous) {
            violations.add("nextSequence must equal the last message sequence");
        }
        requireNone(violations);
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
}
