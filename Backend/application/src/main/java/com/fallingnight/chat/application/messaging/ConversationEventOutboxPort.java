package com.fallingnight.chat.application.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable at-least-once relay lifecycle; event content remains in conversation history. */
public interface ConversationEventOutboxPort {
    List<ConversationEventOutboxClaim> claim(
            UUID owner, Instant claimedAt, Duration lease, int limit);

    boolean markPublished(ConversationEventOutboxClaim claim, Instant publishedAt);

    boolean defer(ConversationEventOutboxClaim claim, Instant failedAt,
            Instant retryAt, String failureCode);
}
