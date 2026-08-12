package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.*;
import java.util.concurrent.*;

/** Detached authenticated V1 room member-directory handler. */
public final class V1RoomMemberListHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomMemberListUseCase members;
    private final V1JsonRoomMemberListCodec codec;
    private final Executor executor;
    private final V1RoomMemberListEventSink events;
    private boolean inFlight;
    public V1RoomMemberListHandler(LegacyV1RoomMemberListUseCase members,
            V1JsonRoomMemberListCodec codec, Executor executor,
            V1RoomMemberListEventSink events) {
        this.members = Objects.requireNonNull(members, "members");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }
    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomMemberListCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonRoomMemberListCodec.RequestKind.LIST || inFlight) {
            failAndClose(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> list(context, identity, request.roomId())); }
        catch (RejectedExecutionException exception) {
            inFlight = false; failAndClose(context, true);
        } catch (RuntimeException exception) {
            inFlight = false; failAndClose(context, false);
        }
    }
    private void list(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            long roomId) {
        long started = System.nanoTime();
        try {
            var result = Objects.requireNonNull(members.list(identity.accountId(), roomId));
            schedule(context, () -> complete(context, identity, result, roomId,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; failAndClose(context, false); });
        }
    }
    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            LegacyV1RoomMemberListResult result, long roomId, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encode(result, roomId); }
        catch (RuntimeException exception) { failAndClose(context, false); return; }
        V1RoomMemberListEventSink.Outcome outcome; int count = 0;
        if (result instanceof LegacyV1RoomMemberListResult.Listed listed) {
            outcome = V1RoomMemberListEventSink.Outcome.LISTED; count = listed.users().size();
        } else outcome = switch ((LegacyV1RoomMemberListResult.Rejected) result) {
            case INVALID_INPUT -> V1RoomMemberListEventSink.Outcome.INVALID_INPUT;
            case ROOM_ACCESS_DENIED -> V1RoomMemberListEventSink.Outcome.ACCESS_DENIED;
            case ROOM_TOO_LARGE -> V1RoomMemberListEventSink.Outcome.ROOM_TOO_LARGE;
        };
        try { events.completed(outcome, count, elapsed); } catch (RuntimeException ignored) { }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }
    private void failAndClose(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room member list unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
