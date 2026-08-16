package com.fallingnight.chat.application.notification;

/** Injected key-custody boundary; implementations must bind ciphertext to account/install context. */
public interface WebPushCredentialProtectionPort {
    ProtectedWebPushSubscription protect(WebPushSubscriptionRegistration registration);
}
