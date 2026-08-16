package com.fallingnight.chat.application.notification;

/** Secret- and identity-free bounded worker result for scheduling and tests. */
public record WebPushWorkerReport(
        Status status,
        int recipientCount,
        int providerAttempts,
        int delivered,
        int invalidSubscriptions,
        int ineligibleRecipients) {
    public WebPushWorkerReport {
        if (status == null || recipientCount < 0 || providerAttempts < 0
                || delivered < 0 || invalidSubscriptions < 0
                || ineligibleRecipients < 0
                || delivered + invalidSubscriptions > providerAttempts
                || ineligibleRecipients > recipientCount) {
            throw new IllegalArgumentException("invalid Web Push worker report");
        }
    }

    public enum Status {
        DISABLED,
        COMPLETED,
        DEFERRED,
        FENCE_LOST
    }
}
