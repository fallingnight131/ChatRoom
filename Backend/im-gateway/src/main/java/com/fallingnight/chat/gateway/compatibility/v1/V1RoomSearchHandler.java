package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Detached authenticated V1 room-search handler. */
public final class V1RoomSearchHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomSearchUseCase search;
    private final V1JsonRoomSearchCodec codec;
    private final Executor executor;
    private final V1RoomSearchEventSink events;
    private boolean inFlight;
    public V1RoomSearchHandler(LegacyV1RoomSearchUseCase search, V1JsonRoomSearchCodec codec,
            Executor executor, V1RoomSearchEventSink events) {
        this.search = Objects.requireNonNull(search); this.codec = Objects.requireNonNull(codec);
        this.executor = Objects.requireNonNull(executor); this.events = Objects.requireNonNull(events);
    }
    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomSearchCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonRoomSearchCodec.RequestKind.SEARCH || inFlight) {
            fail(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> run(context, identity, request.keyword())); }
        catch (RejectedExecutionException exception) { inFlight = false; fail(context, true); }
        catch (RuntimeException exception) { inFlight = false; fail(context, false); }
    }
    private void run(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            String keyword) {
        long started = System.nanoTime();
        try {
            var result = search.search(identity.accountId(), keyword);
            schedule(context, () -> complete(context, identity, result,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }
    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            LegacyV1RoomSearchResult result, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encode(result); }
        catch (RuntimeException exception) { fail(context, false); return; }
        int count = result instanceof LegacyV1RoomSearchResult.Found found
                ? found.rooms().size() : 0;
        var outcome = result instanceof LegacyV1RoomSearchResult.Found
                ? V1RoomSearchEventSink.Outcome.FOUND
                : V1RoomSearchEventSink.Outcome.INPUT_REJECTED;
        try { events.completed(outcome, count, elapsed); } catch (RuntimeException ignored) { }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }
    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room search unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
