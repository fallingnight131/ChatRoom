package com.fallingnight.chat.application.notification;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Authenticated account/device intent to atomically replace one Web Push subscription. */
public final class WebPushSubscriptionRegistration implements AutoCloseable {
    private final UUID accountId;
    private final UUID installationId;
    private final Optional<Instant> browserExpiresAt;
    private final WebPushSubscriptionCredentials credentials;

    private WebPushSubscriptionRegistration(
            UUID accountId,
            UUID installationId,
            Optional<Instant> browserExpiresAt,
            WebPushSubscriptionCredentials credentials) {
        this.accountId = accountId;
        this.installationId = installationId;
        this.browserExpiresAt = browserExpiresAt;
        this.credentials = credentials;
    }

    public static WebPushSubscriptionRegistration copyOf(
            UUID accountId,
            UUID installationId,
            Optional<Instant> browserExpiresAt,
            byte[] endpoint,
            byte[] p256dh,
            byte[] authSecret) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(installationId, "installationId");
        browserExpiresAt = Objects.requireNonNull(browserExpiresAt, "browserExpiresAt");
        browserExpiresAt.ifPresent(expiry -> {
            if (!expiry.isAfter(Instant.EPOCH)) {
                throw new IllegalArgumentException("browserExpiresAt must be after the epoch");
            }
        });
        return new WebPushSubscriptionRegistration(
                accountId,
                installationId,
                browserExpiresAt,
                WebPushSubscriptionCredentials.copyOf(endpoint, p256dh, authSecret));
    }

    public UUID accountId() {
        return accountId;
    }

    public UUID installationId() {
        return installationId;
    }

    public Optional<Instant> browserExpiresAt() {
        return browserExpiresAt;
    }

    public <T> T withEndpointCopy(Function<byte[], T> action) {
        return credentials.withEndpointCopy(action);
    }

    public <T> T withP256dhCopy(Function<byte[], T> action) {
        return credentials.withP256dhCopy(action);
    }

    public <T> T withAuthSecretCopy(Function<byte[], T> action) {
        return credentials.withAuthSecretCopy(action);
    }

    public boolean isClosed() {
        return credentials.isClosed();
    }

    @Override
    public void close() {
        credentials.close();
    }

    @Override
    public String toString() {
        return "WebPushSubscriptionRegistration[accountId=" + accountId
                + ", installationId=" + installationId
                + ", browserExpiresAt=" + browserExpiresAt
                + ", credentials=REDACTED]";
    }
}
