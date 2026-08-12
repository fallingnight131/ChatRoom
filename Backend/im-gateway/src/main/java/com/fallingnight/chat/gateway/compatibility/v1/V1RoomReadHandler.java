package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Detached response-free authenticated V1 room read-cursor handler. */
public final class V1RoomReadHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomReadUseCase reads;
    private final V1JsonRoomReadCodec codec;
    private final Executor executor;
    private final V1RoomReadEventSink events;
    private boolean inFlight;
    public V1RoomReadHandler(LegacyV1RoomReadUseCase reads, V1JsonRoomReadCodec codec,
            Executor executor, V1RoomReadEventSink events) {
        this.reads = Objects.requireNonNull(reads); this.codec = Objects.requireNonNull(codec);
        this.executor = Objects.requireNonNull(executor); this.events = Objects.requireNonNull(events);
    }
    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomReadCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonRoomReadCodec.RequestKind.MARK_READ || inFlight) {
            fail(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> mark(context, identity, request)); }
        catch (RejectedExecutionException e) { inFlight = false; fail(context, true); }
        catch (RuntimeException e) { inFlight = false; fail(context, false); }
    }
    private void mark(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomReadCodec.DecodedRequest request) {
        long started = System.nanoTime();
        try {
            LegacyV1RoomReadResult result = reads.markRead(new LegacyV1RoomReadCommand(
                    identity.accountId(), request.legacyRoomId()));
            schedule(context, () -> complete(context, identity, result,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException e) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }
    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            LegacyV1RoomReadResult result, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        V1RoomReadEventSink.Outcome outcome; long advanced = 0;
        if (result instanceof LegacyV1RoomReadResult.Marked marked) {
            outcome = marked.changed() ? V1RoomReadEventSink.Outcome.ADVANCED
                    : V1RoomReadEventSink.Outcome.UNCHANGED;
            advanced = marked.lastReadSequence() - marked.previousSequence();
        } else outcome = result == LegacyV1RoomReadResult.Rejected.INVALID_ROOM_ID
                ? V1RoomReadEventSink.Outcome.INVALID_ROOM_ID
                : V1RoomReadEventSink.Outcome.ACCESS_DENIED;
        try { events.completed(outcome, advanced, elapsed); } catch (RuntimeException ignored) { }
    }
    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room read unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
