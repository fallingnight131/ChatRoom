package com.fallingnight.chat.gateway.transport;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

/** Sends an empty WebSocket ping only for authenticated writer-idle connections. */
public final class V2AuthenticatedHeartbeatHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof IdleStateEvent idle
                && idle.state() == IdleState.WRITER_IDLE
                && context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get() != null) {
            context.writeAndFlush(new PingWebSocketFrame());
            return;
        }
        context.fireUserEventTriggered(event);
    }
}
