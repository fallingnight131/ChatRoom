package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestRejectionResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestRejectionUseCase;
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

/** Detached authenticated V1 friend-request rejection handler. */
public final class V1FriendRequestRejectionHandler
        extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final String FAILURE_REASON = "V1 friend rejection unavailable";
    private final LegacyV1FriendRequestRejectionUseCase rejection;
    private final V1JsonFriendRequestRejectionCodec codec;
    private final Executor executor;
    private final V1FriendRequestRejectionEventSink events;
    private boolean inFlight;

    public V1FriendRequestRejectionHandler(
            LegacyV1FriendRequestRejectionUseCase rejection,
            V1JsonFriendRequestRejectionCodec codec,
            Executor executor,
            V1FriendRequestRejectionEventSink events) {
        this.rejection = Objects.requireNonNull(rejection, "rejection");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonFriendRequestRejectionCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonFriendRequestRejectionCodec.RequestKind.REJECT || inFlight) {
            failAndClose(context, false); return;
        }
        inFlight = true;
        try {
            executor.execute(() -> rejectOffLoop(context, identity, request.requestId()));
        } catch (RejectedExecutionException exception) {
            inFlight = false; failAndClose(context, true);
        } catch (RuntimeException exception) {
            inFlight = false; failAndClose(context, false);
        }
    }

    private void rejectOffLoop(
            ChannelHandlerContext context,
            LegacyV1AuthenticatedIdentity identity,
            long requestId) {
        long started = System.nanoTime();
        final LegacyV1FriendRequestRejectionResult result;
        try {
            result = rejection.reject(identity.accountId(), requestId);
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
            LegacyV1FriendRequestRejectionResult result,
            long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        boolean success = result instanceof LegacyV1FriendRequestRejectionResult.Accepted;
        byte[] response;
        try { response = codec.encode(success); }
        catch (RuntimeException exception) { failAndClose(context, false); return; }
        var outcome = switch (result) {
            case LegacyV1FriendRequestRejectionResult.Accepted accepted -> accepted.duplicate()
                    ? V1FriendRequestRejectionEventSink.Outcome.DUPLICATE_ACCEPT
                    : V1FriendRequestRejectionEventSink.Outcome.FIRST_ACCEPT;
            case LegacyV1FriendRequestRejectionResult.Rejected ignored ->
                    V1FriendRequestRejectionEventSink.Outcome.REJECTED;
        };
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
