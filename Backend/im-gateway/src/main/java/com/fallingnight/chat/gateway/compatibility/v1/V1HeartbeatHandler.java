package com.fallingnight.chat.gateway.compatibility.v1;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import java.util.Objects;

/** V1 application-heartbeat response and authenticated reader-idle closure. */
public final class V1HeartbeatHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final V1JsonLifecycleCodec codec;

    public V1HeartbeatHandler(V1JsonLifecycleCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        if (context.channel().attr(V1ConnectionAttributes.AUTHENTICATED).get() == null) {
            context.fireChannelRead(frame.retain());
            return;
        }
        V1JsonLifecycleCodec.MessageKind kind = codec.classify(
                ByteBufUtil.getBytes(frame.content()));
        if (kind == V1JsonLifecycleCodec.MessageKind.HEARTBEAT) {
            context.writeAndFlush(new TextWebSocketFrame(
                    Unpooled.wrappedBuffer(codec.encodeHeartbeatAck())));
            return;
        }
        if (kind == V1JsonLifecycleCodec.MessageKind.HEARTBEAT_ACK) {
            return;
        }
        context.fireChannelRead(frame.retain());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof IdleStateEvent idle
                && idle.state() == IdleState.READER_IDLE
                && context.channel().attr(V1ConnectionAttributes.AUTHENTICATED).get() != null) {
            context.writeAndFlush(new CloseWebSocketFrame(
                            WebSocketCloseStatus.ENDPOINT_UNAVAILABLE.code(),
                            "V1 idle timeout"))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        context.fireUserEventTriggered(event);
    }
}
