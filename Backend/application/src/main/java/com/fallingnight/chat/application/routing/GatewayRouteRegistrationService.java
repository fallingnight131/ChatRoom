package com.fallingnight.chat.application.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Gateway boot lease plus catch-up/register/second-repair ordering. */
public final class GatewayRouteRegistrationService {
    public static final Duration MIN_LEASE = Duration.ofSeconds(5);
    public static final Duration MAX_LEASE = Duration.ofMinutes(1);

    private final GatewayRouteLeasePort routes;
    private final UUID gatewayId;
    private final Duration lease;
    private final Clock clock;

    public GatewayRouteRegistrationService(GatewayRouteLeasePort routes, UUID gatewayId,
            Duration lease, Clock clock) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.gatewayId = Objects.requireNonNull(gatewayId, "gatewayId");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (lease.compareTo(MIN_LEASE) < 0 || lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("lease outside reviewed range");
        }
    }

    public boolean renewGateway() {
        Instant now = clock.instant();
        return routes.renewGateway(new GatewayRouteLease(gatewayId, now, now.plus(lease)));
    }

    public Optional<ConversationRouteRegistration> registerAfterCatchUp(UUID conversationId,
            long caughtUpThroughSequence, ConversationRouteRepairPort repair) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(repair, "repair");
        if (caughtUpThroughSequence < 0) {
            throw new IllegalArgumentException("caughtUpThroughSequence must not be negative");
        }
        Instant publishedAt = clock.instant();
        Instant expiresAt = publishedAt.plus(lease);
        var route = new ConversationGatewayRoute(gatewayId, conversationId,
                caughtUpThroughSequence, publishedAt, expiresAt);
        if (!routes.publishConversationRoute(route)) {
            return Optional.empty();
        }
        try {
            long repairedThrough = repair.repairThrough(conversationId, caughtUpThroughSequence);
            if (repairedThrough < caughtUpThroughSequence) {
                throw new IllegalStateException("route repair moved sequence backwards");
            }
            return Optional.of(new ConversationRouteRegistration(
                    conversationId, repairedThrough, expiresAt));
        } catch (RuntimeException exception) {
            try { routes.removeConversationRoute(gatewayId, conversationId); }
            catch (RuntimeException cleanup) { exception.addSuppressed(cleanup); }
            throw exception;
        }
    }

    public boolean removeConversation(UUID conversationId) {
        return routes.removeConversationRoute(gatewayId,
                Objects.requireNonNull(conversationId, "conversationId"));
    }

    public boolean releaseGateway() {
        return routes.releaseGateway(gatewayId);
    }

    public UUID gatewayId() { return gatewayId; }
}
