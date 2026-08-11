package com.fallingnight.chat.gateway.transport;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.Objects;

/** Acquires one connection slot before TLS work and releases it exactly once. */
public final class GatewayConnectionLimitHandler extends ChannelInboundHandlerAdapter {
    private final GatewayConnectionLimiter limiter;
    private boolean acquired;

    public GatewayConnectionLimitHandler(GatewayConnectionLimiter limiter) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        acquired = limiter.tryAcquire();
        if (!acquired) {
            context.close();
            return;
        }
        context.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        if (acquired) {
            acquired = false;
            limiter.release();
        }
        context.fireChannelInactive();
    }
}
