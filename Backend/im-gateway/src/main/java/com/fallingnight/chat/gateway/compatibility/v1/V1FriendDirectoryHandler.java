package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendDirectorySnapshot;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendDirectoryUseCase;
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

/** Detached authenticated V1 friend-directory adapter. */
public final class V1FriendDirectoryHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final String FAILURE_REASON = "V1 friend directory unavailable";

    private final LegacyV1FriendDirectoryUseCase directory;
    private final V1JsonFriendDirectoryCodec codec;
    private final Executor executor;
    private final V1FriendDirectoryEventSink events;
    private boolean inFlight;

    public V1FriendDirectoryHandler(
            LegacyV1FriendDirectoryUseCase directory,
            V1JsonFriendDirectoryCodec codec,
            Executor executor,
            V1FriendDirectoryEventSink events) {
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
        V1JsonFriendDirectoryCodec.RequestKind kind = codec.classify(
                ByteBufUtil.getBytes(frame.content()));
        if (kind == V1JsonFriendDirectoryCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain());
            return;
        }
        if (kind == V1JsonFriendDirectoryCodec.RequestKind.MALFORMED_FRIEND_LIST || inFlight) {
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
        final LegacyV1FriendDirectorySnapshot snapshot;
        try {
            snapshot = directory.listFriends(identity.accountId());
        } catch (RuntimeException exception) {
            schedule(context, () -> {
                inFlight = false;
                failAndClose(context, false);
            });
            return;
        }
        schedule(context, () -> complete(
                context, identity, snapshot, Math.max(0, System.nanoTime() - started)));
    }

    private void complete(
            ChannelHandlerContext context,
            LegacyV1AuthenticatedIdentity identity,
            LegacyV1FriendDirectorySnapshot snapshot,
            long executionNanos) {
        inFlight = false;
        if (!context.channel().isActive()
                || !identity.equals(context.channel()
                        .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try {
            response = codec.encode(snapshot);
        } catch (RuntimeException exception) {
            failAndClose(context, false);
            return;
        }
        recordCompleted(snapshot, executionNanos);
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }

    private void failAndClose(ChannelHandlerContext context, boolean saturated) {
        if (saturated) recordSaturated(); else recordFailed();
        if (!context.channel().isActive()) return;
        context.writeAndFlush(new CloseWebSocketFrame(
                        WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(), FAILURE_REASON))
                .addListener(ChannelFutureListener.CLOSE);
    }

    private void recordCompleted(LegacyV1FriendDirectorySnapshot snapshot, long elapsed) {
        try {
            events.completed(snapshot.friends().size(), snapshot.pendingFriendRequests(), elapsed);
        } catch (RuntimeException ignored) { }
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
