package com.fallingnight.chat.application.routing;

import java.util.List;
import java.util.Objects;

public record GatewayLiveEventBatch(
        String requestedAfterStreamId, List<GatewayLiveEventStreamEntry> entries) {
    public GatewayLiveEventBatch {
        Objects.requireNonNull(requestedAfterStreamId, "requestedAfterStreamId");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (!requestedAfterStreamId.matches("[0-9]+-[0-9]+")
                || entries.stream().anyMatch(Objects::isNull)
                || entries.stream().map(GatewayLiveEventStreamEntry::streamId)
                    .distinct().count() != entries.size()) {
            throw new IllegalArgumentException("invalid gateway live event batch");
        }
    }

    public String nextStreamId() {
        return entries.isEmpty() ? requestedAfterStreamId : entries.getLast().streamId();
    }
}
