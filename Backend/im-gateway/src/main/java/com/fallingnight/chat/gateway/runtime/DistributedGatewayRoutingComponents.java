package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.application.routing.GatewayRouteRegistrationService;
import com.fallingnight.chat.application.messaging.ConversationEventOutboxStatusPort;
import com.fallingnight.chat.gateway.operations.ConversationEventRelayTelemetry;
import com.fallingnight.chat.gateway.operations.DistributedGatewayRoutingRuntime;
import com.fallingnight.chat.gateway.operations.GatewayLiveEventConsumerTelemetry;
import com.fallingnight.chat.gateway.transport.DistributedConversationLiveRouter;
import java.util.Objects;
import java.util.UUID;

/** Immutable handles exposed by the default-off distributed routing composition. */
public record DistributedGatewayRoutingComponents(
        UUID gatewayId,
        DistributedGatewayRoutingRuntime runtime,
        GatewayRouteRegistrationService registration,
        DistributedConversationLiveRouter liveRouter,
        ConversationEventOutboxStatusPort outboxStatus,
        ConversationEventRelayTelemetry relayTelemetry,
        GatewayLiveEventConsumerTelemetry consumerTelemetry) {
    public DistributedGatewayRoutingComponents {
        Objects.requireNonNull(gatewayId, "gatewayId");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(liveRouter, "liveRouter");
        Objects.requireNonNull(outboxStatus, "outboxStatus");
        Objects.requireNonNull(relayTelemetry, "relayTelemetry");
        Objects.requireNonNull(consumerTelemetry, "consumerTelemetry");
    }
}
