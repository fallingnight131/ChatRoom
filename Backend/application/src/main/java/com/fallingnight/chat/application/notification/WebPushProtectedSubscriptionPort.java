package com.fallingnight.chat.application.notification;

import java.time.Instant;
import java.util.UUID;

/** Loads a complete bounded ciphertext-only batch for one current recipient account. */
public interface WebPushProtectedSubscriptionPort {
    ProtectedWebPushSubscriptionBatch loadActive(UUID accountId, Instant observedAt);
}
