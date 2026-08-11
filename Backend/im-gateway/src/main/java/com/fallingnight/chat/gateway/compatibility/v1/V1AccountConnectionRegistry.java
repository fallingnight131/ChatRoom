package com.fallingnight.chat.gateway.compatibility.v1;

import io.netty.channel.Channel;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local V1 single-account connection ownership. */
public final class V1AccountConnectionRegistry {
    private final ConcurrentHashMap<UUID, Channel> connections = new ConcurrentHashMap<>();

    /**
     * Makes {@code channel} authoritative for {@code accountId} and returns the
     * displaced channel, if any. Closing an older channel cannot remove its
     * replacement.
     */
    public Channel replace(UUID accountId, Channel channel) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(channel, "channel");
        AtomicReference<Channel> displaced = new AtomicReference<>();
        connections.compute(accountId, (ignored, current) -> {
            if (current != null && current != channel) {
                displaced.set(current);
            }
            return channel;
        });
        channel.closeFuture().addListener(ignored -> connections.remove(accountId, channel));
        return displaced.get();
    }

    int activeAccountCount() {
        return connections.size();
    }
}
