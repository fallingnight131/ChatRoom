package com.fallingnight.chat.application.routing;

import java.util.Objects;

/** One Redis-positioned payload-free hint; stream position is not a message cursor. */
public record GatewayLiveEventStreamEntry(String streamId, GatewayLiveEventHint hint) {
    public GatewayLiveEventStreamEntry {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(hint, "hint");
        if (!streamId.matches("[0-9]+-[0-9]+")) {
            throw new IllegalArgumentException("invalid gateway event stream id");
        }
    }
}
