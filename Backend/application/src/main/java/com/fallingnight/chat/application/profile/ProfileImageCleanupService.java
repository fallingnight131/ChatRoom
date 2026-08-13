package com.fallingnight.chat.application.profile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** One bounded leased claim-delete-confirm pass. */
public final class ProfileImageCleanupService {
    public static final int MAX_BATCH_SIZE = 1_000;
    public static final Duration MIN_PENDING_AGE = Duration.ofMinutes(10);
    public static final Duration MIN_CLAIM_LEASE = Duration.ofMinutes(5);
    private final ProfileImageCleanupPort cleanup;
    private final ProfileImageObjectDeletionPort objects;
    private final Duration pendingAge;
    private final Duration claimLease;
    private final int batchSize;
    private final Clock clock;

    public ProfileImageCleanupService(ProfileImageCleanupPort cleanup,
            ProfileImageObjectDeletionPort objects, Duration pendingAge,
            Duration claimLease, int batchSize, Clock clock) {
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.pendingAge = requireDuration(pendingAge, MIN_PENDING_AGE, Duration.ofDays(30),
                "pendingAge");
        this.claimLease = requireDuration(claimLease, MIN_CLAIM_LEASE, Duration.ofHours(24),
                "claimLease");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE)
            throw new IllegalArgumentException("batchSize must be in 1..1000");
        this.batchSize = batchSize; this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ProfileImageCleanupReport runOnce() {
        Instant now = clock.instant();
        List<ProfileImageCleanupClaim> claims = List.copyOf(cleanup.claim(
                now.minus(pendingAge), now.minus(claimLease), now, batchSize));
        if (claims.size() > batchSize)
            throw new IllegalStateException("profile cleanup port exceeded requested batch size");
        if (claims.stream().map(ProfileImageCleanupClaim::claimId).distinct().count()
                != claims.size()
                || claims.stream().map(ProfileImageCleanupClaim::objectKey).distinct().count()
                    != claims.size())
            throw new IllegalStateException("profile cleanup port returned duplicate claims");
        int deleted = 0, providerFailures = 0, confirmationFailures = 0;
        for (ProfileImageCleanupClaim claim : claims) {
            try { objects.deleteIfPresent(claim.objectKey()); }
            catch (RuntimeException exception) {
                providerFailures++;
                try { cleanup.release(claim); } catch (RuntimeException ignored) { }
                continue;
            }
            try {
                if (cleanup.confirmDeleted(claim, clock.instant())) deleted++;
                else confirmationFailures++;
            } catch (RuntimeException exception) { confirmationFailures++; }
        }
        return new ProfileImageCleanupReport(
                claims.size(), deleted, providerFailures, confirmationFailures);
    }

    private static Duration requireDuration(Duration value, Duration minimum,
            Duration maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0)
            throw new IllegalArgumentException(name + " outside reviewed range");
        return value;
    }
}
