package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFilesResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFilesUseCase;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
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

/** Detached authenticated administrator-only V1 room-files handler. */
public final class V1RoomFilesHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomFilesUseCase files;
    private final V1JsonRoomFilesCodec codec;
    private final Executor executor;
    private final V1RoomFilesEventSink events;
    private boolean inFlight;

    public V1RoomFilesHandler(
            LegacyV1RoomFilesUseCase files,
            V1JsonRoomFilesCodec codec,
            Executor executor,
            V1RoomFilesEventSink events) {
        this.files = Objects.requireNonNull(files, "files");
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
        V1JsonRoomFilesCodec.DecodedRequest request =
                codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomFilesCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain());
            return;
        }
        if (request.kind() != V1JsonRoomFilesCodec.RequestKind.READ || inFlight) {
            failAndClose(context, false);
            return;
        }
        inFlight = true;
        try {
            executor.execute(() -> read(context, identity, request.roomId()));
        } catch (RejectedExecutionException exception) {
            inFlight = false;
            failAndClose(context, true);
        } catch (RuntimeException exception) {
            inFlight = false;
            failAndClose(context, false);
        }
    }

    private void read(ChannelHandlerContext context,
            LegacyV1AuthenticatedIdentity identity, long roomId) {
        long started = System.nanoTime();
        try {
            LegacyV1RoomFilesResult result = Objects.requireNonNull(
                    files.read(identity.accountId(), roomId));
            schedule(context, () -> complete(context, identity, result, roomId,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> {
                inFlight = false;
                failAndClose(context, false);
            });
        }
    }

    private void complete(ChannelHandlerContext context,
            LegacyV1AuthenticatedIdentity identity,
            LegacyV1RoomFilesResult result,
            long roomId,
            long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) {
            return;
        }
        final byte[] response;
        try {
            response = codec.encode(result, roomId);
        } catch (RuntimeException exception) {
            failAndClose(context, false);
            return;
        }
        V1RoomFilesEventSink.Outcome outcome =
                result instanceof LegacyV1RoomFilesResult.Read
                        ? V1RoomFilesEventSink.Outcome.READ
                        : switch ((LegacyV1RoomFilesResult.Rejected) result) {
                            case INVALID_INPUT -> V1RoomFilesEventSink.Outcome.INVALID_INPUT;
                            case ROOM_ADMIN_REQUIRED ->
                                    V1RoomFilesEventSink.Outcome.ADMIN_REQUIRED;
                        };
        try {
            events.completed(outcome, elapsed);
        } catch (RuntimeException ignored) {
            // Telemetry cannot affect the compatibility response.
        }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }

    private void failAndClose(ChannelHandlerContext context, boolean saturated) {
        try {
            if (saturated) events.saturated();
            else events.failed();
        } catch (RuntimeException ignored) {
            // Closing the failed request must not depend on telemetry.
        }
        if (context.channel().isActive()) {
            context.writeAndFlush(new CloseWebSocketFrame(
                    WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                    "V1 room files unavailable")).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try {
            context.executor().execute(completion);
        } catch (RejectedExecutionException exception) {
            context.close();
        }
    }
}
