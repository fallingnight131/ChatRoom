package com.fallingnight.chat.gateway.operations;

public record GatewayLiveEventConsumerTelemetrySnapshot(
        long runs, long runFailures, long read, long applied, long duplicates,
        long notSubscribed, long failed, int consecutiveFailures,
        long nextDelayMillis) { }
