package com.fallingnight.chat.application.routing;

@FunctionalInterface
public interface LocalConversationHintRepairPort {
    LocalConversationHintResult repair(GatewayLiveEventHint hint);
}
