package com.fallingnight.chat.application.notification;

/** Fixed-cardinality durable subscription replacement outcomes. */
public enum WebPushSubscriptionReplaceResult {
    REPLACED,
    ACCOUNT_UNAVAILABLE,
    LIMIT_REACHED
}
