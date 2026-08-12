package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomDirectoryUseCase;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomSummary;
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

/** Detached authenticated V1 room-directory adapter; runtime composition remains inactive. */
public final class V1RoomDirectoryHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final String FAILURE_REASON = "V1 room directory unavailable";

    private final LegacyV1RoomDirectoryUseCase directory;
    private final V1JsonRoomDirectoryCodec codec;
    private final Executor executor;
    private final V1RoomDirectoryEventSink events;
    private boolean inFlight;

    public V1RoomDirectoryHandler(
            LegacyV1RoomDirectoryUseCase directory,
            V1JsonRoomDirectoryCodec codec,
            Executor executor,
            V1RoomDirectoryEventSink events) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) {
            context.fireChannelRead(frame.retain());
            return;
        }
        V1JsonRoomDirectoryCodec.RequestKind kind = codec.classify(
                ByteBufUtil.getBytes(frame.content()));
        if (kind == V1JsonRoomDirectoryCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain());
            return;
        }
        if (kind == V1JsonRoomDirectoryCodec.RequestKind.MALFORMED_ROOM_LIST || inFlight) {
            failAndClose(context, false);
            return;
        }
        inFlight = true;
        try {
            executor.execute(() -> listOffEventLoop(context, identity));
        } catch (RejectedExecutionException exception) {
            inFlight = false;
            failAndClose(context, true);
        } catch (RuntimeException exception) {
            inFlight = false;
            failAndClose(context, false);
        }
    }

    private void listOffEventLoop(
            ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity) {
        long started = System.nanoTime();
        final List<LegacyV1RoomSummary> rooms;
        try {
            rooms = directory.listRooms(identity.accountId());
        } catch (RuntimeException exception) {
            schedule(context, () -> {
                inFlight = false;
                failAndClose(context, false);
            });
            return;
        }
        schedule(context, () -> complete(
                context, identity, rooms, Math.max(0, System.nanoTime() - started)));
    }

    private void complete(
            ChannelHandlerContext context,
            LegacyV1AuthenticatedIdentity identity,
            List<LegacyV1RoomSummary> rooms,
            long executionNanos) {
        inFlight = false;
        if (!context.channel().isActive()
                || !identity.equals(context.channel()
                        .attr(V1ConnectionAttributes.AUTHENTICATED).get())) {
            return;
        }
        final byte[] response;
        try {
            response = codec.encode(rooms);
        } catch (RuntimeException exception) {
            failAndClose(context, false);
            return;
        }
        recordCompleted(rooms.size(), executionNanos);
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }

    private void failAndClose(ChannelHandlerContext context, boolean saturated) {
        if (saturated) recordSaturated();
        else recordFailed();
        if (!context.channel().isActive()) return;
        context.writeAndFlush(new CloseWebSocketFrame(
                        WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(), FAILURE_REASON))
                .addListener(ChannelFutureListener.CLOSE);
    }

    private void recordCompleted(int count, long elapsed) {
        try { events.completed(count, elapsed); } catch (RuntimeException ignored) { }
    }

    private void recordFailed() {
        try { events.failed(); } catch (RuntimeException ignored) { }
    }

    private void recordSaturated() {
        try { events.saturated(); } catch (RuntimeException ignored) { }
    }

    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try {
            context.executor().execute(completion);
        } catch (RejectedExecutionException exception) {
            context.close();
        }
    }
}
