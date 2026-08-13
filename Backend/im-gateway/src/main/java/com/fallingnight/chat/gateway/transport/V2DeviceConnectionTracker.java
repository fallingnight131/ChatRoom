package com.fallingnight.chat.gateway.transport;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.Objects;

/** Registers authenticated channels for best-effort process-local revocation disconnect. */
public final class V2DeviceConnectionTracker extends ChannelInboundHandlerAdapter {
    private final DeviceConnectionRegistry registry;
    private AuthenticatedConnection registered;

    public V2DeviceConnectionTracker(DeviceConnectionRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event == V2ConnectionPhaseEvent.AUTHENTICATED && registered == null) {
            registered = context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get();
            if (registered != null) {
                registry.register(registered.deviceId(), context.channel());
            }
        }
        context.fireUserEventTriggered(event);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        if (registered != null) {
            registry.unregister(registered.deviceId(), context.channel());
            registered = null;
        }
        context.fireChannelInactive();
    }
}
