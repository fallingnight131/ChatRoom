package com.fallingnight.chat.application.notification;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Account-free transport-neutral request whose credential bytes are owned and zeroable. */
public final class WebPushSubscriptionMutationRequest implements AutoCloseable {
    private final UUID installationId;
    private final Optional<Instant> browserExpiresAt;
    private final WebPushSubscriptionCredentials credentials;

    private WebPushSubscriptionMutationRequest(
            UUID installationId,
            Optional<Instant> browserExpiresAt,
            WebPushSubscriptionCredentials credentials) {
        this.installationId = installationId;
        this.browserExpiresAt = browserExpiresAt;
        this.credentials = credentials;
    }

    public static WebPushSubscriptionMutationRequest copyOf(
            UUID installationId,
            Optional<Instant> browserExpiresAt,
            byte[] endpoint,
            byte[] p256dh,
            byte[] authSecret) {
        Objects.requireNonNull(installationId, "installationId");
        browserExpiresAt = Objects.requireNonNull(browserExpiresAt, "browserExpiresAt");
        browserExpiresAt.ifPresent(expiry -> {
            if (!expiry.isAfter(Instant.EPOCH)) {
                throw new IllegalArgumentException("browserExpiresAt must be after the epoch");
            }
        });
        return new WebPushSubscriptionMutationRequest(
                installationId,
                browserExpiresAt,
                WebPushSubscriptionCredentials.copyOf(endpoint, p256dh, authSecret));
    }

    public UUID installationId() { return installationId; }

    public Optional<Instant> browserExpiresAt() { return browserExpiresAt; }

    WebPushSubscriptionRegistration bindTo(UUID authenticatedAccountId) {
        Objects.requireNonNull(authenticatedAccountId, "authenticatedAccountId");
        return credentials.withEndpointCopy(endpoint -> credentials.withP256dhCopy(p256dh ->
                credentials.withAuthSecretCopy(auth -> WebPushSubscriptionRegistration.copyOf(
                        authenticatedAccountId, installationId, browserExpiresAt,
                        endpoint, p256dh, auth))));
    }

    public boolean isClosed() { return credentials.isClosed(); }

    @Override
    public void close() { credentials.close(); }

    @Override
    public String toString() {
        return "WebPushSubscriptionMutationRequest[installationId=" + installationId
                + ", browserExpiresAt=" + browserExpiresAt + ", credentials=REDACTED]";
    }
}
