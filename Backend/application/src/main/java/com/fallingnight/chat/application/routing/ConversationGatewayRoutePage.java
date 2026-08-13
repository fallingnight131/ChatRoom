package com.fallingnight.chat.application.routing;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded target gateways; incomplete results must never be partially published. */
public record ConversationGatewayRoutePage(List<UUID> gatewayIds, boolean complete) {
    public ConversationGatewayRoutePage {
        gatewayIds = List.copyOf(Objects.requireNonNull(gatewayIds, "gatewayIds"));
        if (gatewayIds.stream().anyMatch(Objects::isNull)
                || gatewayIds.stream().distinct().count() != gatewayIds.size()) {
            throw new IllegalArgumentException("invalid conversation gateway route page");
        }
    }
}
