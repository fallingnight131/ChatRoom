package com.fallingnight.chat.gateway.transport;

import io.netty.channel.Channel;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local connection index; durable PostgreSQL revocation remains authoritative. */
public final class DeviceConnectionRegistry {
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Channel, Boolean>> devices =
            new ConcurrentHashMap<>();

    void register(UUID deviceId, Channel channel) {
        devices.computeIfAbsent(deviceId, ignored -> new ConcurrentHashMap<>())
                .put(channel, Boolean.TRUE);
    }

    void unregister(UUID deviceId, Channel channel) {
        devices.computeIfPresent(deviceId, (ignored, channels) -> {
            channels.remove(channel);
            return channels.isEmpty() ? null : channels;
        });
    }

    public int close(UUID deviceId) {
        ConcurrentHashMap<Channel, Boolean> channels = devices.remove(deviceId);
        if (channels == null) {
            return 0;
        }
        int count = channels.size();
        channels.keySet().forEach(Channel::close);
        return count;
    }
}
