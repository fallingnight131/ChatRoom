package com.fallingnight.chat.application.routing;

import java.time.Instant;
import java.util.UUID;

/** Reconstructable gateway and conversation leases; never an authorization source. */
public interface GatewayRouteLeasePort {
    boolean renewGateway(GatewayRouteLease lease);

    boolean publishConversationRoute(ConversationGatewayRoute route);

    ConversationGatewayRoutePage findConversationGateways(
            UUID conversationId, Instant observedAt, int limit);

    boolean removeConversationRoute(UUID gatewayId, UUID conversationId);

    boolean releaseGateway(UUID gatewayId);
}
