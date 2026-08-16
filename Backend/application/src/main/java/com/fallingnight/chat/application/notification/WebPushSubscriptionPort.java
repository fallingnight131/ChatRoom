package com.fallingnight.chat.application.notification;

import java.util.UUID;

/** Durable authenticated subscription mutations; adapters must encrypt credentials at rest. */
public interface WebPushSubscriptionPort {
    void replace(WebPushSubscriptionRegistration registration);

    boolean delete(UUID accountId, UUID installationId);
}
