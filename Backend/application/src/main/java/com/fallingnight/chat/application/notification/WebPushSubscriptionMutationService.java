package com.fallingnight.chat.application.notification;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Binds an authenticated account to default-off, admitted subscription mutations. */
public final class WebPushSubscriptionMutationService {
    private final WebPushDeliveryPolicy deliveryPolicy;
    private final WebPushSubscriptionAdmissionPort admission;
    private final WebPushSubscriptionPort subscriptions;
    private final Clock clock;

    public WebPushSubscriptionMutationService(
            WebPushDeliveryPolicy deliveryPolicy,
            WebPushSubscriptionAdmissionPort admission,
            WebPushSubscriptionPort subscriptions,
            Clock clock) {
        this.deliveryPolicy = Objects.requireNonNull(deliveryPolicy, "deliveryPolicy");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Consumes and closes {@code request} on every outcome. */
    public WebPushSubscriptionMutationResult replace(
            UUID authenticatedAccountId,
            WebPushSubscriptionMutationRequest request) {
        Objects.requireNonNull(authenticatedAccountId, "authenticatedAccountId");
        Objects.requireNonNull(request, "request");
        try (request) {
            if (!deliveryPolicy.enabled()) {
                return WebPushSubscriptionMutationResult.of(
                        WebPushSubscriptionMutationResult.Outcome.DISABLED);
            }
            WebPushSubscriptionMutationResult denied = admit(
                    authenticatedAccountId,
                    request.installationId(),
                    WebPushSubscriptionMutationAction.REPLACE);
            if (denied != null) return denied;
            try (WebPushSubscriptionRegistration registration =
                    request.bindTo(authenticatedAccountId)) {
                return switch (subscriptions.replace(registration)) {
                    case REPLACED -> WebPushSubscriptionMutationResult.of(
                            WebPushSubscriptionMutationResult.Outcome.REPLACED);
                    case ACCOUNT_UNAVAILABLE -> WebPushSubscriptionMutationResult.of(
                            WebPushSubscriptionMutationResult.Outcome.ACCOUNT_UNAVAILABLE);
                    case LIMIT_REACHED -> WebPushSubscriptionMutationResult.of(
                            WebPushSubscriptionMutationResult.Outcome.LIMIT_REACHED);
                };
            }
        }
    }

    public WebPushSubscriptionMutationResult delete(
            UUID authenticatedAccountId, UUID installationId) {
        Objects.requireNonNull(authenticatedAccountId, "authenticatedAccountId");
        Objects.requireNonNull(installationId, "installationId");
        if (!deliveryPolicy.enabled()) {
            return WebPushSubscriptionMutationResult.of(
                    WebPushSubscriptionMutationResult.Outcome.DISABLED);
        }
        WebPushSubscriptionMutationResult denied = admit(
                authenticatedAccountId,
                installationId,
                WebPushSubscriptionMutationAction.DELETE);
        if (denied != null) return denied;
        return WebPushSubscriptionMutationResult.of(subscriptions.delete(
                authenticatedAccountId, installationId)
                ? WebPushSubscriptionMutationResult.Outcome.DELETED
                : WebPushSubscriptionMutationResult.Outcome.UNCHANGED);
    }

    private WebPushSubscriptionMutationResult admit(
            UUID accountId,
            UUID installationId,
            WebPushSubscriptionMutationAction action) {
        WebPushSubscriptionAdmissionDecision decision = Objects.requireNonNull(
                admission.admit(accountId, installationId, action, clock.instant()),
                "admissionDecision");
        if (decision instanceof WebPushSubscriptionAdmissionDecision.RateLimited limited) {
            return WebPushSubscriptionMutationResult.rateLimited(limited.retryAfter());
        }
        return null;
    }
}
