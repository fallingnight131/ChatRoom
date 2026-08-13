package com.fallingnight.chat.gateway.operations;

public record ConversationEventRelayTelemetrySnapshot(
        long runs,
        long runFailures,
        long claimed,
        long published,
        long deferred,
        long ownershipLost,
        long publisherFailures,
        int consecutiveFailures,
        long nextDelayMillis) { }
