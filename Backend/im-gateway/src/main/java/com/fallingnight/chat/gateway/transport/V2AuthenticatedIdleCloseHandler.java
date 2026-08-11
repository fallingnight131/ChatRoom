package com.fallingnight.chat.gateway.transport;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

/** Closes only authenticated connections when the upstream reader-idle timer fires. */
public final class V2AuthenticatedIdleCloseHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof IdleStateEvent idle
                && idle.state() == IdleState.READER_IDLE
                && context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get() != null) {
            context.writeAndFlush(new CloseWebSocketFrame(
                            WebSocketCloseStatus.ENDPOINT_UNAVAILABLE.code(),
                            "V2 idle timeout"))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        context.fireUserEventTriggered(event);
    }
}
