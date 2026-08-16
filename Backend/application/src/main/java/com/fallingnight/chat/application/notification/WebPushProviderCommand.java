package com.fallingnight.chat.application.notification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Generic encrypted-payload inputs; registration secrets stay in the owned registration. */
public record WebPushProviderCommand(
        WebPushSubscriptionRegistration registration,
        UUID notificationId,
        UUID conversationId,
        UUID messageId,
        boolean mentioned,
        Instant expiresAt) {
    public static final int PAYLOAD_VERSION = 1;

    public WebPushProviderCommand {
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(notificationId, "notificationId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!notificationId.equals(messageId)) {
            throw new IllegalArgumentException("notificationId must equal stable messageId");
        }
    }

    @Override
    public String toString() {
        return "WebPushProviderCommand[payloadVersion=" + PAYLOAD_VERSION
                + ", mentioned=" + mentioned + ", identifiers=REDACTED, credentials=REDACTED]";
    }
}
