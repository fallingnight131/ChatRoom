package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Detached authenticated V1 room-history reader. */
public final class V1RoomHistoryHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomHistoryUseCase history;
    private final V1JsonRoomHistoryCodec codec;
    private final Executor executor;
    private final V1RoomHistoryEventSink events;
    private boolean inFlight;
    public V1RoomHistoryHandler(LegacyV1RoomHistoryUseCase history,
            V1JsonRoomHistoryCodec codec, Executor executor, V1RoomHistoryEventSink events) {
        this.history = Objects.requireNonNull(history); this.codec = Objects.requireNonNull(codec);
        this.executor = Objects.requireNonNull(executor); this.events = Objects.requireNonNull(events);
    }
    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel().attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomHistoryCodec.RequestKind.OTHER) { context.fireChannelRead(frame.retain()); return; }
        if (request.kind() != V1JsonRoomHistoryCodec.RequestKind.HISTORY || inFlight) { fail(context, false); return; }
        inFlight = true;
        try { executor.execute(() -> read(context, identity, request)); }
        catch (RejectedExecutionException exception) { inFlight = false; fail(context, true); }
        catch (RuntimeException exception) { inFlight = false; fail(context, false); }
    }
    private void read(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomHistoryCodec.DecodedRequest request) {
        long started = System.nanoTime();
        try {
            LegacyV1RoomHistoryResult result = history.read(new LegacyV1RoomHistoryQuery(
                    identity.accountId(), request.roomId(), request.limit(),
                    request.beforeEpochMillis(), request.afterSequence()));
            schedule(context, () -> complete(context, identity, request, result,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }
    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomHistoryCodec.DecodedRequest request, LegacyV1RoomHistoryResult result, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encode(result, request.roomId()); }
        catch (RuntimeException exception) { fail(context, false); return; }
        int count = result instanceof LegacyV1RoomHistoryResult.Page page
                ? page.messages().size() + page.events().size() : 0;
        V1RoomHistoryEventSink.Outcome outcome = result instanceof LegacyV1RoomHistoryResult.Page
                ? V1RoomHistoryEventSink.Outcome.PAGE
                : switch ((LegacyV1RoomHistoryResult.Rejected) result) {
                    case ROOM_ACCESS_DENIED -> V1RoomHistoryEventSink.Outcome.ACCESS_DENIED;
                    case INVALID_SEQUENCE_CURSOR -> V1RoomHistoryEventSink.Outcome.INVALID_CURSOR;
                    case INVALID_REQUEST -> V1RoomHistoryEventSink.Outcome.INVALID_REQUEST;
                };
        try { events.completed(outcome, count, request.afterSequence() != null, elapsed); }
        catch (RuntimeException ignored) { }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }
    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); } catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(), "V1 room history unavailable"))
                .addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); } catch (RejectedExecutionException e) { context.close(); }
    }
}
