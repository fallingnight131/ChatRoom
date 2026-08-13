package com.fallingnight.chat.application.routing;

import java.util.UUID;

/** Completes authoritative post-route sequence repair and returns contiguous progress. */
@FunctionalInterface
public interface ConversationRouteRepairPort {
    long repairThrough(UUID conversationId, long afterSequence);
}
