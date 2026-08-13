package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Detached post-commit V1 room-dissolution handler. */
public final class V1RoomDissolutionHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomDissolutionUseCase rooms;
    private final V1JsonRoomDissolutionCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1RoomDissolutionEventSink events;
    private boolean inFlight;

    public V1RoomDissolutionHandler(LegacyV1RoomDissolutionUseCase rooms,
            V1JsonRoomDissolutionCodec codec, V1AccountConnectionRegistry connections,
            Executor executor, V1RoomDissolutionEventSink events) {
        this.rooms = Objects.requireNonNull(rooms); this.codec = Objects.requireNonNull(codec);
        this.connections = Objects.requireNonNull(connections);
        this.executor = Objects.requireNonNull(executor); this.events = Objects.requireNonNull(events);
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomDissolutionCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() == V1JsonRoomDissolutionCodec.RequestKind.MALFORMED || inFlight) {
            fail(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> execute(context, identity, request.roomId())); }
        catch (RejectedExecutionException exception) { inFlight = false; fail(context, true); }
        catch (RuntimeException exception) { inFlight = false; fail(context, false); }
    }

    private void execute(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            long roomId) {
        long started = System.nanoTime();
        try {
            LegacyV1RoomDissolutionResult result = rooms.dissolve(identity.accountId(), roomId);
            schedule(context, () -> complete(context, identity, roomId, result,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            long roomId, LegacyV1RoomDissolutionResult result, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encodeResponse(result, roomId); }
        catch (RuntimeException exception) { fail(context, false); return; }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
        int routed = 0; V1RoomDissolutionEventSink.Outcome outcome;
        if (result instanceof LegacyV1RoomDissolutionResult.Dissolved dissolved) {
            if (!dissolved.changed()) outcome = V1RoomDissolutionEventSink.Outcome.ALREADY_DISSOLVED;
            else {
                final byte[] notification;
                try { notification = codec.encodeNotification(dissolved, identity.displayName()); }
                catch (RuntimeException exception) { fail(context, false); return; }
                Set<UUID> active = connections.activeAccountIds();
                for (UUID recipient : dissolved.affectedAccountIds()) {
                    if (active.contains(recipient) && connections.executeIfActive(recipient,
                            channel -> channel.writeAndFlush(new TextWebSocketFrame(
                                    Unpooled.wrappedBuffer(notification.clone()))))) routed++;
                }
                outcome = routed == 0
                        ? V1RoomDissolutionEventSink.Outcome.DISSOLVED_NO_LOCAL_RECIPIENT
                        : V1RoomDissolutionEventSink.Outcome.DISSOLVED_ROUTED;
            }
        } else outcome = switch ((LegacyV1RoomDissolutionResult.Rejected) result) {
            case INVALID_INPUT -> V1RoomDissolutionEventSink.Outcome.INVALID_INPUT;
            case ROOM_ADMIN_REQUIRED -> V1RoomDissolutionEventSink.Outcome.ADMIN_REQUIRED;
            case NOT_FOUND -> V1RoomDissolutionEventSink.Outcome.NOT_FOUND;
        };
        try { events.completed(outcome, routed, elapsed); } catch (RuntimeException ignored) { }
    }

    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room dissolution unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
