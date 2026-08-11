package com.fallingnight.chat.gateway.compatibility.v1;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.ScheduledFuture;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Installs V1 application handlers only after the exact guarded WebSocket upgrade. */
public final class V1WebSocketUpgradeHandler extends ChannelInboundHandlerAdapter {
    private final V1ApplicationPipelineInstaller installer;
    private final long upgradeTimeoutMillis;
    private ScheduledFuture<?> deadline;

    public V1WebSocketUpgradeHandler(
            V1ApplicationPipelineInstaller installer, Duration upgradeTimeout) {
        this.installer = Objects.requireNonNull(installer, "installer");
        Objects.requireNonNull(upgradeTimeout, "upgradeTimeout");
        upgradeTimeoutMillis = upgradeTimeout.toMillis();
        if (upgradeTimeoutMillis <= 0) {
            throw new IllegalArgumentException("upgradeTimeout must be at least 1 ms");
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
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete complete) {
            cancel();
            if (!Boolean.TRUE.equals(context.channel()
                            .attr(V1ConnectionAttributes.WEB_UPGRADE_ACCEPTED)
                            .get())
                    || !V1WebSocketEndpointPolicyHandler.WEB_PATH.equals(
                            complete.requestUri())
                    || !V1WebSocketEndpointPolicyHandler.SUBPROTOCOL.equals(
                            complete.selectedSubprotocol())) {
                context.writeAndFlush(new CloseWebSocketFrame(
                                WebSocketCloseStatus.POLICY_VIOLATION.code(),
                                "V1 upgrade policy mismatch"))
                        .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
                return;
            }
            installer.install(context.pipeline());
            context.pipeline().remove(this);
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
            context.close();
        }, upgradeTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void cancel() {
        if (deadline != null) {
            deadline.cancel(false);
            deadline = null;
        }
    }
}
