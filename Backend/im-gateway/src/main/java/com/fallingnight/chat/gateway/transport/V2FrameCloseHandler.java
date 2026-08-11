package com.fallingnight.chat.gateway.transport;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;

/** Converts known unsafe frame failures into fixed WebSocket close outcomes. */
final class V2FrameCloseHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        if (!(cause instanceof V2FrameException failure)) {
            context.fireExceptionCaught(cause);
            return;
        }

        WebSocketCloseStatus status;
        String safeReason;
        if (failure.reason() == V2FrameException.Reason.FRAME_TOO_LARGE) {
            status = WebSocketCloseStatus.MESSAGE_TOO_BIG;
            safeReason = "V2 frame too large";
        } else {
            status = WebSocketCloseStatus.PROTOCOL_ERROR;
            safeReason = "invalid V2 frame";
        }
        context.writeAndFlush(new CloseWebSocketFrame(status.code(), safeReason))
                .addListener(ChannelFutureListener.CLOSE);
    }
}
