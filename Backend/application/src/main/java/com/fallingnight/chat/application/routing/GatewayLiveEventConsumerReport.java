package com.fallingnight.chat.application.routing;

import java.util.Objects;

public record GatewayLiveEventConsumerReport(
        int read, int applied, int duplicates, int notSubscribed,
        int failed, String nextStreamId) {
    public GatewayLiveEventConsumerReport {
        Objects.requireNonNull(nextStreamId, "nextStreamId");
        if (read < 0 || applied < 0 || duplicates < 0 || notSubscribed < 0 || failed < 0
                || applied + duplicates + notSubscribed + failed != read
                || failed > 1 || !nextStreamId.matches("[0-9]+-[0-9]+")) {
            throw new IllegalArgumentException("invalid gateway live event consumer report");
        }
    }
}
