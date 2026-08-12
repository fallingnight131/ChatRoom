package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;

/** Raw V1 contact request retained for deterministic validation and fingerprinting. */
public record V1ContactRequestRow(
        long legacyRequestId,
        long requesterUserId,
        long recipientUserId,
        String status,
        Instant createdAt) {}
