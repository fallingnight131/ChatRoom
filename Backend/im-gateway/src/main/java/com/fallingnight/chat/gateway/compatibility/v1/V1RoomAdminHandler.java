package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.Objects;
import java.util.concurrent.*;

/** Detached authenticated V1 room administrator handler. */
public final class V1RoomAdminHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomAdminUseCase roles;
    private final V1JsonRoomAdminCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1RoomAdminEventSink events;
    private boolean inFlight;

    public V1RoomAdminHandler(LegacyV1RoomAdminUseCase roles, V1JsonRoomAdminCodec codec,
            V1AccountConnectionRegistry connections, Executor executor,
            V1RoomAdminEventSink events) {
        this.roles = Objects.requireNonNull(roles); this.codec = Objects.requireNonNull(codec);
        this.connections = Objects.requireNonNull(connections);
        this.executor = Objects.requireNonNull(executor); this.events = Objects.requireNonNull(events);
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        var identity = context.channel().attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomAdminCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonRoomAdminCodec.RequestKind.CHANGE || inFlight) {
            fail(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> execute(context, identity, request)); }
        catch (RejectedExecutionException exception) { inFlight = false; fail(context, true); }
        catch (RuntimeException exception) { inFlight = false; fail(context, false); }
    }

    private void execute(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomAdminCodec.DecodedRequest request) {
        long started = System.nanoTime();
        try {
            var result = roles.change(new LegacyV1RoomAdminCommand(identity.accountId(),
                    request.roomId(), request.targetUsername(), request.admin()));
            schedule(context, () -> complete(context, identity, request, result,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomAdminCodec.DecodedRequest request, LegacyV1RoomAdminResult result,
            long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        try {
            context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(
                    codec.response(result, request.roomId(), request.targetUsername()))));
            V1RoomAdminEventSink.Outcome outcome;
            if (result instanceof LegacyV1RoomAdminResult.Changed changed) {
                if (!changed.changed()) outcome = V1RoomAdminEventSink.Outcome.UNCHANGED;
                else {
                    byte[] status = codec.status(changed);
                    boolean routed = connections.executeIfActive(changed.targetAccountId(),
                            channel -> channel.writeAndFlush(new TextWebSocketFrame(
                                    Unpooled.wrappedBuffer(status))));
                    outcome = routed ? V1RoomAdminEventSink.Outcome.CHANGED_ROUTED
                            : V1RoomAdminEventSink.Outcome.CHANGED_NO_LOCAL_TARGET;
                }
            } else outcome = V1RoomAdminEventSink.Outcome.REJECTED;
            events.completed(outcome, elapsed);
        } catch (RuntimeException exception) { try { events.failed(); } catch (RuntimeException ignored) { } }
    }

    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room administrator unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); } catch (RejectedExecutionException e) { context.close(); }
    }
}
