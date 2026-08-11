package com.fallingnight.chat.gateway.transport;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.util.concurrent.ScheduledFuture;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Enforces bounded time to negotiate and authenticate a V2 connection. */
public final class V2ConnectionTimeoutHandler extends ChannelInboundHandlerAdapter {
    private final long handshakeTimeoutMs;
    private final long authenticationTimeoutMs;
    private ScheduledFuture<?> handshakeDeadline;
    private ScheduledFuture<?> authenticationDeadline;

    public V2ConnectionTimeoutHandler(
            Duration handshakeTimeout, Duration authenticationTimeout) {
        handshakeTimeoutMs = requirePositiveMillis(handshakeTimeout, "handshakeTimeout");
        authenticationTimeoutMs = requirePositiveMillis(
                authenticationTimeout, "authenticationTimeout");
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        if (context.channel().isActive()) {
            scheduleHandshake(context);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        if (handshakeDeadline == null) {
            scheduleHandshake(context);
        }
        context.fireChannelActive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event == V2ConnectionPhaseEvent.NEGOTIATED) {
            cancel(handshakeDeadline);
            handshakeDeadline = null;
            authenticationDeadline = context.executor().schedule(
                    () -> closeForTimeout(context, "V2 authentication timeout"),
                    authenticationTimeoutMs,
                    TimeUnit.MILLISECONDS);
        } else if (event == V2ConnectionPhaseEvent.AUTHENTICATED) {
            cancel(authenticationDeadline);
            authenticationDeadline = null;
        }
        context.fireUserEventTriggered(event);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        cancelDeadlines();
        context.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) {
        cancelDeadlines();
    }

    private void scheduleHandshake(ChannelHandlerContext context) {
        handshakeDeadline = context.executor().schedule(
                () -> closeForTimeout(context, "V2 handshake timeout"),
                handshakeTimeoutMs,
                TimeUnit.MILLISECONDS);
    }

    private static void closeForTimeout(ChannelHandlerContext context, String safeReason) {
        if (!context.channel().isActive()) {
            return;
        }
        context.writeAndFlush(new CloseWebSocketFrame(
                        WebSocketCloseStatus.POLICY_VIOLATION.code(), safeReason))
                .addListener(ChannelFutureListener.CLOSE);
    }

    private void cancelDeadlines() {
        cancel(handshakeDeadline);
        cancel(authenticationDeadline);
        handshakeDeadline = null;
        authenticationDeadline = null;
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private static long requirePositiveMillis(Duration value, String name) {
        Objects.requireNonNull(value, name);
        long millis = value.toMillis();
        if (millis <= 0) {
            throw new IllegalArgumentException(name + " must be at least 1 ms");
        }
        return millis;
    }
}
