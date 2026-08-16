package com.fallingnight.chat.application.notification;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Payload-free durable notification intent keyed stably by the committed message ID. */
public record WebPushNotificationIntent(
        UUID messageId,
        UUID conversationId,
        UUID senderAccountId,
        Instant committedAt,
        Instant expiresAt,
        Set<UUID> mentionedAccountIds) {
    public static final Duration MAX_LIFETIME = Duration.ofHours(24);
    public static final int MAX_MENTIONED_ACCOUNTS = 100;

    public WebPushNotificationIntent {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(senderAccountId, "senderAccountId");
        Objects.requireNonNull(committedAt, "committedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        mentionedAccountIds = Set.copyOf(
                Objects.requireNonNull(mentionedAccountIds, "mentionedAccountIds"));
        Instant maximumExpiry;
        try {
            maximumExpiry = committedAt.plus(MAX_LIFETIME);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("committedAt cannot define a bounded expiry", exception);
        }
        if (!expiresAt.isAfter(committedAt) || expiresAt.isAfter(maximumExpiry)) {
            throw new IllegalArgumentException("notification lifetime must be in (0, 24h]");
        }
        if (mentionedAccountIds.size() > MAX_MENTIONED_ACCOUNTS
                || mentionedAccountIds.contains(senderAccountId)) {
            throw new IllegalArgumentException("invalid mentionedAccountIds");
        }
    }
}
