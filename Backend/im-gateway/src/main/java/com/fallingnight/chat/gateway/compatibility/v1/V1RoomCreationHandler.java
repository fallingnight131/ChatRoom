package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Detached authenticated idempotent V1 room-creation handler. */
public final class V1RoomCreationHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomCreationUseCase rooms;
    private final V1JsonRoomCreationCodec codec;
    private final Executor executor;
    private final V1RoomCreationEventSink events;
    private boolean inFlight;
    public V1RoomCreationHandler(LegacyV1RoomCreationUseCase rooms,
            V1JsonRoomCreationCodec codec, Executor executor, V1RoomCreationEventSink events) {
        this.rooms = Objects.requireNonNull(rooms); this.codec = Objects.requireNonNull(codec);
        this.executor = Objects.requireNonNull(executor); this.events = Objects.requireNonNull(events);
    }
    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomCreationCodec.RequestKind.OTHER) {
            request.close(); context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonRoomCreationCodec.RequestKind.CREATE || inFlight) {
            request.close(); fail(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> create(context, identity, request)); }
        catch (RejectedExecutionException exception) {
            request.close(); inFlight = false; fail(context, true);
        } catch (RuntimeException exception) {
            request.close(); inFlight = false; fail(context, false);
        }
    }
    private void create(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomCreationCodec.DecodedRequest request) {
        long started = System.nanoTime(); byte[] password = request.passwordCopy();
        try (request; LegacyV1RoomCreationCommand command = new LegacyV1RoomCreationCommand(
                identity.accountId(), request.clientRequestId(), request.roomName(), password)) {
            LegacyV1RoomCreationResult result = rooms.create(command);
            schedule(context, () -> complete(context, identity, result,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        } finally { if (password != null) Arrays.fill(password, (byte) 0); }
    }
    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            LegacyV1RoomCreationResult result, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encode(result); }
        catch (RuntimeException exception) { fail(context, false); return; }
        V1RoomCreationEventSink.Outcome outcome = result instanceof
                LegacyV1RoomCreationResult.Created created
                ? (created.duplicate() ? V1RoomCreationEventSink.Outcome.DUPLICATE
                        : V1RoomCreationEventSink.Outcome.FIRST_CREATED)
                : switch ((LegacyV1RoomCreationResult.Rejected) result) {
                    case INVALID_INPUT -> V1RoomCreationEventSink.Outcome.INVALID_INPUT;
                    case CREATION_DENIED -> V1RoomCreationEventSink.Outcome.DENIED;
                    case CLIENT_REQUEST_ID_CONFLICT -> V1RoomCreationEventSink.Outcome.CONFLICT;
                };
        try { events.completed(outcome, elapsed); } catch (RuntimeException ignored) { }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }
    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room creation unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
