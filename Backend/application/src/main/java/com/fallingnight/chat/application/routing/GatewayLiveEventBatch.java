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
        String previous = requestedAfterStreamId;
        for (GatewayLiveEventStreamEntry entry : entries) {
            if (compareStreamIds(entry.streamId(), previous) <= 0) {
                throw new IllegalArgumentException("gateway event stream ids are not ordered");
            }
            previous = entry.streamId();
        }
    }

    public String nextStreamId() {
        return entries.isEmpty() ? requestedAfterStreamId : entries.getLast().streamId();
    }

    private static int compareStreamIds(String first, String second) {
        String[] left = first.split("-", -1), right = second.split("-", -1);
        try {
            int milliseconds = Long.compareUnsigned(
                    Long.parseUnsignedLong(left[0]), Long.parseUnsignedLong(right[0]));
            return milliseconds != 0 ? milliseconds : Long.compareUnsigned(
                    Long.parseUnsignedLong(left[1]), Long.parseUnsignedLong(right[1]));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid gateway event stream id", exception);
        }
    }
}
