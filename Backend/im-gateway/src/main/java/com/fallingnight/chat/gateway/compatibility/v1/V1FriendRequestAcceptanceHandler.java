package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestAcceptanceResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestAcceptanceUseCase;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Detached authenticated V1 friend-request acceptance and online notification. */
public final class V1FriendRequestAcceptanceHandler
        extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final String FAILURE_REASON = "V1 friend acceptance unavailable";
    private final LegacyV1FriendRequestAcceptanceUseCase acceptance;
    private final V1JsonFriendRequestAcceptanceCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1FriendRequestAcceptanceEventSink events;
    private boolean inFlight;

    public V1FriendRequestAcceptanceHandler(
            LegacyV1FriendRequestAcceptanceUseCase acceptance,
            V1JsonFriendRequestAcceptanceCodec codec,
            V1AccountConnectionRegistry connections,
            Executor executor,
            V1FriendRequestAcceptanceEventSink events) {
        this.acceptance = Objects.requireNonNull(acceptance, "acceptance");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonFriendRequestAcceptanceCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonFriendRequestAcceptanceCodec.RequestKind.ACCEPT || inFlight) {
            failAndClose(context, false); return;
        }
        inFlight = true;
        try {
            executor.execute(() -> acceptOffLoop(context, identity, request.requestId()));
        } catch (RejectedExecutionException exception) {
            inFlight = false; failAndClose(context, true);
        } catch (RuntimeException exception) {
            inFlight = false; failAndClose(context, false);
        }
    }

    private void acceptOffLoop(
            ChannelHandlerContext context,
            LegacyV1AuthenticatedIdentity identity,
            long requestId) {
        long started = System.nanoTime();
        final LegacyV1FriendRequestAcceptanceResult result;
        try {
            result = acceptance.accept(identity.accountId(), requestId);
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; failAndClose(context, false); });
            return;
        }
        schedule(context, () -> complete(context, identity, result,
                Math.max(0, System.nanoTime() - started)));
    }

    private void complete(
            ChannelHandlerContext context,
            LegacyV1AuthenticatedIdentity identity,
            LegacyV1FriendRequestAcceptanceResult result,
            long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        boolean success = result instanceof LegacyV1FriendRequestAcceptanceResult.Accepted;
        byte[] response;
        try { response = codec.encodeResponse(success); }
        catch (RuntimeException exception) { failAndClose(context, false); return; }

        V1FriendRequestAcceptanceEventSink.Outcome outcome;
        if (result instanceof LegacyV1FriendRequestAcceptanceResult.Accepted accepted) {
            if (accepted.duplicate()) {
                outcome = V1FriendRequestAcceptanceEventSink.Outcome.DUPLICATE;
            } else {
                final byte[] notification;
                try {
                    notification = codec.encodeNotification(
                            identity.username(), identity.displayName());
                } catch (RuntimeException exception) {
                    failAndClose(context, false); return;
                }
                boolean online = connections.executeIfActive(
                        accepted.requesterAccountId(), channel -> channel.writeAndFlush(
                                new TextWebSocketFrame(Unpooled.wrappedBuffer(notification))));
                outcome = online
                        ? V1FriendRequestAcceptanceEventSink.Outcome.FIRST_ROUTE_SCHEDULED
                        : V1FriendRequestAcceptanceEventSink.Outcome.FIRST_NO_LOCAL_ROUTE;
            }
        } else {
            outcome = V1FriendRequestAcceptanceEventSink.Outcome.REJECTED;
        }
        try { events.completed(outcome, elapsed); } catch (RuntimeException ignored) { }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }

    private void failAndClose(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (!context.channel().isActive()) return;
        context.writeAndFlush(new CloseWebSocketFrame(
                        WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(), FAILURE_REASON))
                .addListener(ChannelFutureListener.CLOSE);
    }

    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
