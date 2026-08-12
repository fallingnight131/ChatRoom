package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1PendingFriendRequest;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1PendingFriendRequestUseCase;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Detached authenticated V1 pending-request list handler. */
public final class V1PendingFriendRequestHandler
        extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final String FAILURE_REASON = "V1 pending requests unavailable";
    private final LegacyV1PendingFriendRequestUseCase requests;
    private final V1JsonPendingFriendRequestCodec codec;
    private final Executor executor;
    private final V1PendingFriendRequestEventSink events;
    private boolean inFlight;

    public V1PendingFriendRequestHandler(
            LegacyV1PendingFriendRequestUseCase requests,
            V1JsonPendingFriendRequestCodec codec,
            Executor executor,
            V1PendingFriendRequestEventSink events) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var kind = codec.classify(ByteBufUtil.getBytes(frame.content()));
        if (kind == V1JsonPendingFriendRequestCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (kind == V1JsonPendingFriendRequestCodec.RequestKind.MALFORMED_PENDING || inFlight) {
            failAndClose(context, false); return;
        }
        inFlight = true;
        try {
            executor.execute(() -> readOffLoop(context, identity));
        } catch (RejectedExecutionException exception) {
            inFlight = false; failAndClose(context, true);
        } catch (RuntimeException exception) {
            inFlight = false; failAndClose(context, false);
        }
    }

    private void readOffLoop(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity) {
        long started = System.nanoTime();
        final List<LegacyV1PendingFriendRequest> result;
        try {
            result = requests.listPending(identity.accountId());
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
            List<LegacyV1PendingFriendRequest> result,
            long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encode(result); }
        catch (RuntimeException exception) { failAndClose(context, false); return; }
        try { events.completed(result.size(), elapsed); } catch (RuntimeException ignored) { }
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
