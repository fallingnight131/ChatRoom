package com.fallingnight.chat.application.notification;

/** Fixed-cardinality terminal event outcomes; never include provider response text. */
public enum WebPushTerminalOutcome {
    DELIVERED,
    EXPIRED,
    INELIGIBLE,
    INVALID_SUBSCRIPTION
}
