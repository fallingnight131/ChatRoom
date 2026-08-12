package com.fallingnight.chat.application.attachment;

/** Fixed-label counters for one bounded cleanup pass. */
public record AttachmentCleanupReport(
        int revoked,
        int attempted,
        int deleted,
        int providerFailures,
        int confirmationFailures) {
    public AttachmentCleanupReport {
        if (revoked < 0 || attempted < 0 || deleted < 0
                || providerFailures < 0 || confirmationFailures < 0
                || deleted + providerFailures + confirmationFailures != attempted) {
            throw new IllegalArgumentException("cleanup report counters are inconsistent");
        }
    }
}
