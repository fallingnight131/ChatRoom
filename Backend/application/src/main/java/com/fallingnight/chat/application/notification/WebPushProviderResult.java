package com.fallingnight.chat.application.notification;

/** Fixed provider outcomes; response bodies and endpoints never cross this boundary. */
public enum WebPushProviderResult {
    DELIVERED,
    INVALID_SUBSCRIPTION,
    TRANSIENT_FAILURE,
    AUTHENTICATION_FAILURE
}
