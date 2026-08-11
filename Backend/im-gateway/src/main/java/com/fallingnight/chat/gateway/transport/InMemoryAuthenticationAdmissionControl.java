package com.fallingnight.chat.gateway.transport;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Thread-safe fixed-window limits for one gateway process. */
public final class InMemoryAuthenticationAdmissionControl
        implements AuthenticationAdmissionControl {
    private static final String UNKNOWN_KEY = "<unknown>";

    private final AuthenticationAdmissionLimits limits;
    private final Clock clock;
    private final Bucket gateway = new Bucket();
    private final Map<String, Bucket> directPeers = new HashMap<>();
    private final Map<String, Bucket> accounts = new HashMap<>();
    private final long[] denials = new long[AuthenticationLimitDimension.values().length];
    private long allowedAttempts;
    private long deniedAttempts;
    private long lastCleanupMs = -1;

    public InMemoryAuthenticationAdmissionControl(
            AuthenticationAdmissionLimits limits, Clock clock) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized AuthenticationAdmissionDecision acquire(
            String directPeer, String presentedUsername) {
        long now = clock.millis();
        cleanupExpired(now);
        long retry = consume(gateway, limits.gatewayAttempts(), now);
        if (retry > 0) {
            return deny(AuthenticationLimitDimension.GATEWAY, retry);
        }

        AuthenticationAdmissionDecision peerDecision = consumeKeyed(
                directPeers,
                normalizePeer(directPeer),
                limits.directPeerAttempts(),
                AuthenticationLimitDimension.DIRECT_PEER,
                AuthenticationLimitDimension.DIRECT_PEER_CAPACITY,
                now);
        if (!peerDecision.allowed()) {
            return peerDecision;
        }

        AuthenticationAdmissionDecision accountDecision = consumeKeyed(
                accounts,
                normalizeAccount(presentedUsername),
                limits.accountAttempts(),
                AuthenticationLimitDimension.ACCOUNT,
                AuthenticationLimitDimension.ACCOUNT_CAPACITY,
                now);
        if (!accountDecision.allowed()) {
            return accountDecision;
        }
        allowedAttempts++;
        return AuthenticationAdmissionDecision.allow();
    }

    @Override
    public synchronized void recordSuccess(String presentedUsername) {
        accounts.remove(normalizeAccount(presentedUsername));
    }

    public synchronized AuthenticationAdmissionSnapshot snapshot() {
        return new AuthenticationAdmissionSnapshot(
                allowedAttempts,
                deniedAttempts,
                denials[AuthenticationLimitDimension.GATEWAY.ordinal()],
                denials[AuthenticationLimitDimension.DIRECT_PEER.ordinal()],
                denials[AuthenticationLimitDimension.ACCOUNT.ordinal()],
                denials[AuthenticationLimitDimension.DIRECT_PEER_CAPACITY.ordinal()]
                        + denials[AuthenticationLimitDimension.ACCOUNT_CAPACITY.ordinal()],
                directPeers.size(),
                accounts.size());
    }

    private AuthenticationAdmissionDecision consumeKeyed(
            Map<String, Bucket> buckets,
            String key,
            int limit,
            AuthenticationLimitDimension limitDimension,
            AuthenticationLimitDimension capacityDimension,
            long now) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= limits.maxTrackedKeys()) {
                cleanupExpired(now);
            }
            if (buckets.size() >= limits.maxTrackedKeys()) {
                return deny(capacityDimension, limits.window().toMillis());
            }
            bucket = new Bucket();
            buckets.put(key, bucket);
        }
        long retry = consume(bucket, limit, now);
        return retry == 0
                ? AuthenticationAdmissionDecision.allow()
                : deny(limitDimension, retry);
    }

    private long consume(Bucket bucket, int limit, long now) {
        long elapsed = now - bucket.windowStartedMs;
        long windowMs = limits.window().toMillis();
        if (bucket.windowStartedMs < 0 || elapsed < 0 || elapsed >= windowMs) {
            bucket.windowStartedMs = now;
            bucket.attempts = 0;
        }
        if (bucket.attempts >= limit) {
            return Math.max(1, windowMs - (now - bucket.windowStartedMs));
        }
        bucket.attempts++;
        return 0;
    }

    private AuthenticationAdmissionDecision deny(
            AuthenticationLimitDimension dimension, long retryAfterMs) {
        deniedAttempts++;
        denials[dimension.ordinal()]++;
        return AuthenticationAdmissionDecision.deny(dimension, retryAfterMs);
    }

    private void cleanupExpired(long now) {
        long windowMs = limits.window().toMillis();
        if (lastCleanupMs >= 0 && now >= lastCleanupMs && now - lastCleanupMs < windowMs) {
            return;
        }
        lastCleanupMs = now;
        directPeers.values().removeIf(bucket -> expired(bucket, now, windowMs));
        accounts.values().removeIf(bucket -> expired(bucket, now, windowMs));
    }

    private static boolean expired(Bucket bucket, long now, long windowMs) {
        long elapsed = now - bucket.windowStartedMs;
        return bucket.windowStartedMs < 0 || elapsed < 0 || elapsed >= windowMs;
    }

    private static String normalizeAccount(String account) {
        if (account == null) {
            return UNKNOWN_KEY;
        }
        String normalized = account.strip().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? UNKNOWN_KEY : normalized;
    }

    private static String normalizePeer(String peer) {
        if (peer == null || peer.isBlank()) {
            return UNKNOWN_KEY;
        }
        if (!peer.matches("[0-9a-fA-F:.%]+")) {
            return UNKNOWN_KEY;
        }
        try {
            return InetAddress.getByName(peer).getHostAddress();
        } catch (UnknownHostException exception) {
            return UNKNOWN_KEY;
        }
    }

    private static final class Bucket {
        private long windowStartedMs = -1;
        private int attempts;
    }
}
