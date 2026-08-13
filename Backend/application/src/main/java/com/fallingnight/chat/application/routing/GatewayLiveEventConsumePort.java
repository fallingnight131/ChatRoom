package com.fallingnight.chat.application.routing;

import java.util.UUID;

@FunctionalInterface
public interface GatewayLiveEventConsumePort {
    GatewayLiveEventBatch readAfter(UUID gatewayId, String afterStreamId, int limit);
}
