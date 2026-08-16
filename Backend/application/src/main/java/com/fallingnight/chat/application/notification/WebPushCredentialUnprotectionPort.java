package com.fallingnight.chat.application.notification;

/** Decrypts one context-bound subscription for a short-lived provider attempt. */
public interface WebPushCredentialUnprotectionPort {
    WebPushSubscriptionRegistration unprotect(ProtectedWebPushSubscription subscription);
}
