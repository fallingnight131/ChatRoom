package com.fallingnight.chat.application.notification;

import java.time.Instant;

/** Computes one bounded retry time with operations-owned backoff and jitter policy. */
public interface WebPushRetrySchedulePort {
    Instant nextRetry(WebPushOutboxClaim claim, Instant failedAt);
}
