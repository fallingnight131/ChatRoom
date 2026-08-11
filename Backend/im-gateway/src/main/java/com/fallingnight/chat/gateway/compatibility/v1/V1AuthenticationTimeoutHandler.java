package com.fallingnight.chat.gateway.compatibility.v1;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.util.concurrent.ScheduledFuture;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Bounds time from a future V1 WebSocket upgrade to successful login. */
public final class V1AuthenticationTimeoutHandler extends ChannelInboundHandlerAdapter {
    private final long timeoutMillis;
    private ScheduledFuture<?> deadline;

    public V1AuthenticationTimeoutHandler(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        timeoutMillis = timeout.toMillis();
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeout must be at least 1 ms");
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        if (context.channel().isActive()) {
            schedule(context);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        if (deadline == null) {
            schedule(context);
        }
        context.fireChannelActive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event == V1ConnectionPhaseEvent.AUTHENTICATED) {
            cancel();
        }
        context.fireUserEventTriggered(event);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        cancel();
        context.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) {
        cancel();
    }

    private void schedule(ChannelHandlerContext context) {
        deadline = context.executor().schedule(() -> {
            if (!context.channel().isActive()
                    || context.channel().attr(V1ConnectionAttributes.AUTHENTICATED).get() != null) {
                return;
            }
            context.writeAndFlush(new CloseWebSocketFrame(
                            WebSocketCloseStatus.POLICY_VIOLATION.code(),
                            "V1 authentication timeout"))
                    .addListener(ChannelFutureListener.CLOSE);
        }, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void cancel() {
        if (deadline != null) {
            deadline.cancel(false);
            deadline = null;
        }
    }
}
