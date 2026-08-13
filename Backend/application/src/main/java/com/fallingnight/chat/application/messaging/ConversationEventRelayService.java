package com.fallingnight.chat.application.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One scheduler-neutral, bounded outbox claim-publish-complete pass. */
public final class ConversationEventRelayService {
    public static final int MAX_BATCH_SIZE = 100;
    public static final Duration MIN_LEASE = Duration.ofSeconds(1);
    public static final Duration MAX_LEASE = Duration.ofMinutes(5);
    public static final Duration MIN_RETRY_DELAY = Duration.ofMillis(100);
    public static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);

    private final ConversationEventOutboxPort outbox;
    private final ConversationEventPublicationPort publisher;
    private final UUID owner;
    private final Duration lease;
    private final int batchSize;
    private final Duration initialRetryDelay;
    private final Duration maximumRetryDelay;
    private final Clock clock;

    public ConversationEventRelayService(ConversationEventOutboxPort outbox,
            ConversationEventPublicationPort publisher, UUID owner, Duration lease,
            int batchSize, Duration initialRetryDelay, Duration maximumRetryDelay,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.lease = requireRange(lease, MIN_LEASE, MAX_LEASE, "lease");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be in 1..100");
        }
        this.batchSize = batchSize;
        this.initialRetryDelay = requireRange(initialRetryDelay,
                MIN_RETRY_DELAY, MAX_RETRY_DELAY, "initialRetryDelay");
        this.maximumRetryDelay = requireRange(maximumRetryDelay,
                initialRetryDelay, MAX_RETRY_DELAY, "maximumRetryDelay");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ConversationEventRelayReport runOnce() {
        Instant claimedAt = clock.instant();
        List<ConversationEventOutboxClaim> claims = List.copyOf(
                outbox.claim(owner, claimedAt, lease, batchSize));
        validateClaims(claims, claimedAt);

        int published = 0;
        int deferred = 0;
        int ownershipLost = 0;
        int publisherFailures = 0;
        for (ConversationEventOutboxClaim claim : claims) {
            ConversationEventPublicationOutcome outcome;
            boolean publisherFailed = false;
            try {
                outcome = Objects.requireNonNull(publisher.publish(claim),
                        "publisher outcome");
            } catch (RuntimeException exception) {
                outcome = ConversationEventPublicationOutcome.DEPENDENCY_UNAVAILABLE;
                publisherFailed = true;
                publisherFailures++;
            }

            Instant completedAt = clock.instant();
            if (completedAt.isAfter(claim.claimExpiresAt())) {
                ownershipLost++;
                continue;
            }
            if (outcome.published()) {
                if (outbox.markPublished(claim, completedAt)) {
                    published++;
                } else {
                    ownershipLost++;
                }
                continue;
            }
            String failureCode = publisherFailed
                    ? "PUBLISHER_FAILURE" : outcome.failureCode();
            Instant retryAt = completedAt.plus(retryDelay(claim.attemptCount()));
            if (outbox.defer(claim, completedAt, retryAt, failureCode)) {
                deferred++;
            } else {
                ownershipLost++;
            }
        }
        return new ConversationEventRelayReport(
                claims.size(), published, deferred, ownershipLost, publisherFailures);
    }

    private void validateClaims(List<ConversationEventOutboxClaim> claims, Instant claimedAt) {
        if (claims.size() > batchSize
                || claims.stream().map(ConversationEventOutboxClaim::eventId)
                    .distinct().count() != claims.size()
                || claims.stream().map(ConversationEventOutboxClaim::claimId)
                    .distinct().count() != claims.size()
                || claims.stream().anyMatch(claim -> !claim.claimOwner().equals(owner)
                    || !claim.claimedAt().equals(claimedAt)
                    || !claim.claimExpiresAt().equals(claimedAt.plus(lease)))) {
            throw new IllegalStateException("outbox port returned invalid relay claims");
        }
    }

    private Duration retryDelay(int attemptCount) {
        int exponent = Math.min(attemptCount - 1, 20);
        long delayMillis = initialRetryDelay.toMillis() * (1L << exponent);
        return Duration.ofMillis(Math.min(delayMillis, maximumRetryDelay.toMillis()));
    }

    private static Duration requireRange(
            Duration value, Duration minimum, Duration maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " outside reviewed range");
        }
        return value;
    }
}
