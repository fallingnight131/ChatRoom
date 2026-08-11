package com.fallingnight.chat.gateway.transport;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/** Last-resort safe channel failure signal without peer, request, or secret data. */
public final class GatewayChannelExceptionHandler extends ChannelInboundHandlerAdapter {
    private static final System.Logger LOGGER =
            System.getLogger(GatewayChannelExceptionHandler.class.getName());

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        String category = cause == null ? "unknown" : cause.getClass().getSimpleName();
        LOGGER.log(System.Logger.Level.WARNING, "event=gateway_channel_error type=" + category);
        context.close();
    }
}
