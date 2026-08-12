package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectReadCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectReadResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectReadUseCase;
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

/** Detached response-free V1 private read handler with mapped peer notification. */
public final class V1DirectReadHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1DirectReadUseCase reads;
    private final V1JsonDirectReadCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1DirectReadEventSink events;
    private boolean inFlight;
    public V1DirectReadHandler(LegacyV1DirectReadUseCase reads, V1JsonDirectReadCodec codec,
            V1AccountConnectionRegistry connections, Executor executor,
            V1DirectReadEventSink events) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonDirectReadCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonDirectReadCodec.RequestKind.MARK_READ || inFlight) {
            fail(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> mark(context, identity, request)); }
        catch (RejectedExecutionException exception) { inFlight = false; fail(context, true); }
        catch (RuntimeException exception) { inFlight = false; fail(context, false); }
    }

    private void mark(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonDirectReadCodec.DecodedRequest request) {
        long started = System.nanoTime();
        try {
            LegacyV1DirectReadResult result = reads.markRead(new LegacyV1DirectReadCommand(
                    identity.accountId(), request.legacyFriendshipId()));
            schedule(context, () -> complete(context, identity, result,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            LegacyV1DirectReadResult result, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        V1DirectReadEventSink.Outcome outcome; long advanced = 0;
        if (result instanceof LegacyV1DirectReadResult.Marked marked) {
            final byte[] notification;
            try { notification = codec.encodeNotification(marked, identity.username()); }
            catch (RuntimeException exception) { fail(context, false); return; }
            boolean routed = !marked.targetAccountId().equals(identity.accountId())
                    && connections.executeIfActive(marked.targetAccountId(), channel ->
                    channel.writeAndFlush(new TextWebSocketFrame(
                            Unpooled.wrappedBuffer(notification))));
            if (marked.changed()) {
                outcome = routed ? V1DirectReadEventSink.Outcome.ADVANCED_ROUTE_SCHEDULED
                        : V1DirectReadEventSink.Outcome.ADVANCED_NO_LOCAL_ROUTE;
            } else {
                outcome = routed ? V1DirectReadEventSink.Outcome.UNCHANGED_ROUTE_SCHEDULED
                        : V1DirectReadEventSink.Outcome.UNCHANGED_NO_LOCAL_ROUTE;
            }
            advanced = marked.lastReadSequence() - marked.previousSequence();
        } else outcome = result == LegacyV1DirectReadResult.Rejected.INVALID_FRIENDSHIP_ID
                ? V1DirectReadEventSink.Outcome.INVALID_ID
                : V1DirectReadEventSink.Outcome.ACCESS_DENIED;
        try { events.completed(outcome, advanced, elapsed); }
        catch (RuntimeException ignored) { }
    }

    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 direct read unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
