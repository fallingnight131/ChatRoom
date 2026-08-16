package com.fallingnight.chat.application.notification;

import java.time.Instant;
import java.util.UUID;

/** Abuse-control boundary keyed only by authenticated account, installation, and action. */
public interface WebPushSubscriptionAdmissionPort {
    WebPushSubscriptionAdmissionDecision admit(
            UUID accountId,
            UUID installationId,
            WebPushSubscriptionMutationAction action,
            Instant observedAt);
}
