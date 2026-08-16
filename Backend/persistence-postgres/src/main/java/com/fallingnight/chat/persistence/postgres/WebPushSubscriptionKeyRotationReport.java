package com.fallingnight.chat.persistence.postgres;

import java.util.Objects;
import java.util.Set;

/** Identity-free result of one offline transactional subscription key rewrite. */
public record WebPushSubscriptionKeyRotationReport(
        int rotatedSubscriptions,
        Set<String> sourceEncryptionKeyIds,
        String targetEncryptionKeyId) {
    public WebPushSubscriptionKeyRotationReport {
        sourceEncryptionKeyIds = Set.copyOf(Objects.requireNonNull(
                sourceEncryptionKeyIds, "sourceEncryptionKeyIds"));
        Objects.requireNonNull(targetEncryptionKeyId, "targetEncryptionKeyId");
        if (rotatedSubscriptions < 0
                || (rotatedSubscriptions == 0) != sourceEncryptionKeyIds.isEmpty()
                || !targetEncryptionKeyId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("invalid Web Push key rotation report");
        }
    }
}
