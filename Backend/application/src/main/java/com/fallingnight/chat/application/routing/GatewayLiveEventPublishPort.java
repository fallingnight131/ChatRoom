package com.fallingnight.chat.application.routing;

@FunctionalInterface
public interface GatewayLiveEventPublishPort {
    PublishResult publish(GatewayLiveEventHint hint, int maximumStreamLength);

    enum PublishResult {
        PUBLISHED,
        DEPENDENCY_UNAVAILABLE,
        DEPENDENCY_REJECTED
    }
}
