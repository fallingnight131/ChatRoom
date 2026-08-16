package com.fallingnight.chat.application.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bounded fenced lifecycle over transactionally produced notification intents. */
public interface WebPushOutboxPort {
    List<WebPushOutboxClaim> claim(
            UUID owner, Instant claimedAt, Duration lease, int limit);

    boolean complete(
            WebPushOutboxClaim claim, Instant completedAt, WebPushTerminalOutcome outcome);

    boolean defer(
            WebPushOutboxClaim claim, Instant failedAt, Instant retryAt, String failureCode);

    int expire(Instant observedAt, int limit);

    int purgeCompletedBefore(Instant cutoff, int limit);
}
