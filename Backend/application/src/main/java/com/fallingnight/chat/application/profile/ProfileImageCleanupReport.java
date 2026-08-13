package com.fallingnight.chat.application.profile;

public record ProfileImageCleanupReport(int claimed, int deleted,
        int providerFailures, int confirmationFailures) {
    public ProfileImageCleanupReport {
        if (claimed < 0 || deleted < 0 || providerFailures < 0 || confirmationFailures < 0
                || deleted + providerFailures + confirmationFailures != claimed)
            throw new IllegalArgumentException("invalid profile image cleanup report");
    }
}
