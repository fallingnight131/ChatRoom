package com.fallingnight.chat.application.notification;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded best-effort delivery orchestration with per-attempt current-policy checks. */
public final class WebPushDeliveryWorkerService {
    public static final int MAX_PROVIDER_ATTEMPTS =
            WebPushRecipientResolution.MAX_RECIPIENTS
                    * ProtectedWebPushSubscriptionBatch.MAX_SUBSCRIPTIONS;

    private final WebPushDeliveryPolicy deliveryPolicy;
    private final WebPushRecipientPolicyPort recipients;
    private final WebPushProtectedSubscriptionPort subscriptions;
    private final WebPushCredentialUnprotectionPort unprotection;
    private final WebPushProviderPort provider;
    private final WebPushSubscriptionPort subscriptionMutations;
    private final WebPushOutboxPort outbox;
    private final WebPushRetrySchedulePort retrySchedule;
    private final WebPushWorkerEventSink events;

    public WebPushDeliveryWorkerService(
            WebPushDeliveryPolicy deliveryPolicy,
            WebPushRecipientPolicyPort recipients,
            WebPushProtectedSubscriptionPort subscriptions,
            WebPushCredentialUnprotectionPort unprotection,
            WebPushProviderPort provider,
            WebPushSubscriptionPort subscriptionMutations,
            WebPushOutboxPort outbox,
            WebPushRetrySchedulePort retrySchedule,
            WebPushWorkerEventSink events) {
        this.deliveryPolicy = Objects.requireNonNull(deliveryPolicy, "deliveryPolicy");
        this.recipients = Objects.requireNonNull(recipients, "recipients");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.unprotection = Objects.requireNonNull(unprotection, "unprotection");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.subscriptionMutations = Objects.requireNonNull(
                subscriptionMutations, "subscriptionMutations");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.retrySchedule = Objects.requireNonNull(retrySchedule, "retrySchedule");
        this.events = Objects.requireNonNull(events, "events");
    }

    public WebPushWorkerReport process(WebPushOutboxClaim claim, Instant observedAt) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(observedAt, "observedAt");
        if (!deliveryPolicy.enabled()) {
            return report(WebPushWorkerReport.Status.DISABLED, 0, 0, 0, 0, 0);
        }
        if (observedAt.isBefore(claim.claimedAt())
                || observedAt.isAfter(claim.claimExpiresAt())) {
            events.fenceLost();
            return report(WebPushWorkerReport.Status.FENCE_LOST, 0, 0, 0, 0, 0);
        }

        WebPushRecipientResolution resolution;
        try {
            resolution = Objects.requireNonNull(recipients.resolve(
                    claim.intent(), WebPushRecipientResolution.MAX_RECIPIENTS),
                    "recipientResolution");
        } catch (RuntimeException exception) {
            events.transientFailure();
            return defer(claim, observedAt, "RECIPIENT_POLICY_FAILURE", 0, 0, 0, 0, 0);
        }
        if (resolution == WebPushRecipientResolution.Saturated.INSTANCE) {
            events.recipientSaturated();
            return defer(claim, observedAt, "RECIPIENT_SATURATED", 0, 0, 0, 0, 0);
        }
        List<WebPushRecipient> resolved =
                ((WebPushRecipientResolution.Complete) resolution).recipients();
        int attempts = 0;
        int delivered = 0;
        int invalid = 0;
        int ineligible = 0;
        boolean retry = false;
        String failureCode = "PROVIDER_TRANSIENT";

        for (WebPushRecipient recipient : resolved) {
            boolean recipientEligible = true;
            try (ProtectedWebPushSubscriptionBatch batch = subscriptions.loadActive(
                    recipient.accountId(), observedAt)) {
                if (batch.subscriptions().isEmpty()) {
                    ineligible++;
                    events.ineligible();
                    continue;
                }
                for (ProtectedWebPushSubscription protectedSubscription
                        : batch.subscriptions()) {
                    if (attempts >= MAX_PROVIDER_ATTEMPTS) {
                        retry = true;
                        failureCode = "WORKER_SATURATED";
                        events.recipientSaturated();
                        break;
                    }
                    Optional<WebPushRecipient> current;
                    try {
                        current = Objects.requireNonNull(recipients.reauthorize(
                                claim.intent(), recipient.accountId()),
                                "recipientReauthorization");
                    } catch (RuntimeException exception) {
                        retry = true;
                        failureCode = "RECIPIENT_POLICY_FAILURE";
                        events.transientFailure();
                        break;
                    }
                    if (current.isEmpty()) {
                        recipientEligible = false;
                        ineligible++;
                        events.ineligible();
                        break;
                    }
                    attempts++;
                    WebPushSubscriptionRegistration unprotected;
                    try {
                        unprotected = Objects.requireNonNull(
                                unprotection.unprotect(protectedSubscription),
                                "unprotectedSubscription");
                    } catch (RuntimeException exception) {
                        retry = true;
                        failureCode = "CREDENTIAL_AUTHENTICATION";
                        events.authenticationFailure();
                        continue;
                    }
                    try (WebPushSubscriptionRegistration registration = unprotected) {
                        WebPushProviderResult result;
                        try {
                            result = Objects.requireNonNull(provider.deliver(
                                    new WebPushProviderCommand(
                                            registration,
                                            claim.intent().messageId(),
                                            claim.intent().conversationId(),
                                            claim.intent().messageId(),
                                            current.orElseThrow().mentioned(),
                                            claim.intent().expiresAt())),
                                    "providerResult");
                        } catch (RuntimeException exception) {
                            retry = true;
                            failureCode = "PROVIDER_TRANSIENT";
                            events.transientFailure();
                            continue;
                        }
                        switch (result) {
                            case DELIVERED -> {
                                delivered++;
                                events.delivered();
                            }
                            case INVALID_SUBSCRIPTION -> {
                                invalid++;
                                events.invalidSubscription();
                                try {
                                    subscriptionMutations.delete(recipient.accountId(),
                                            registration.installationId());
                                } catch (RuntimeException exception) {
                                    retry = true;
                                    failureCode = "SUBSCRIPTION_DELETE_FAILURE";
                                    events.transientFailure();
                                }
                            }
                            case TRANSIENT_FAILURE -> {
                                retry = true;
                                failureCode = "PROVIDER_TRANSIENT";
                                events.transientFailure();
                            }
                            case AUTHENTICATION_FAILURE -> {
                                retry = true;
                                failureCode = "PROVIDER_AUTHENTICATION";
                                events.authenticationFailure();
                            }
                        }
                    }
                }
            } catch (RuntimeException exception) {
                retry = true;
                failureCode = "SUBSCRIPTION_STORAGE_FAILURE";
                events.transientFailure();
            }
            if (!recipientEligible) continue;
            if (attempts >= MAX_PROVIDER_ATTEMPTS) break;
        }

        if (retry) {
            return defer(claim, observedAt, failureCode, resolved.size(), attempts,
                    delivered, invalid, ineligible);
        }
        WebPushTerminalOutcome outcome = delivered > 0
                ? WebPushTerminalOutcome.DELIVERED
                : invalid > 0
                ? WebPushTerminalOutcome.INVALID_SUBSCRIPTION
                : WebPushTerminalOutcome.INELIGIBLE;
        boolean completed = outbox.complete(claim, observedAt, outcome);
        if (!completed) {
            events.fenceLost();
            return report(WebPushWorkerReport.Status.FENCE_LOST, resolved.size(),
                    attempts, delivered, invalid, ineligible);
        }
        events.completed(outcome);
        return report(WebPushWorkerReport.Status.COMPLETED, resolved.size(),
                attempts, delivered, invalid, ineligible);
    }

    private WebPushWorkerReport defer(
            WebPushOutboxClaim claim,
            Instant failedAt,
            String failureCode,
            int recipientCount,
            int attempts,
            int delivered,
            int invalid,
            int ineligible) {
        Instant retryAt = Objects.requireNonNull(
                retrySchedule.nextRetry(claim, failedAt), "retryAt");
        if (!outbox.defer(claim, failedAt, retryAt, failureCode)) {
            events.fenceLost();
            return report(WebPushWorkerReport.Status.FENCE_LOST,
                    recipientCount, attempts, delivered, invalid, ineligible);
        }
        events.deferred();
        return report(WebPushWorkerReport.Status.DEFERRED,
                recipientCount, attempts, delivered, invalid, ineligible);
    }

    private static WebPushWorkerReport report(
            WebPushWorkerReport.Status status,
            int recipientCount,
            int attempts,
            int delivered,
            int invalid,
            int ineligible) {
        return new WebPushWorkerReport(
                status, recipientCount, attempts, delivered, invalid, ineligible);
    }
}
