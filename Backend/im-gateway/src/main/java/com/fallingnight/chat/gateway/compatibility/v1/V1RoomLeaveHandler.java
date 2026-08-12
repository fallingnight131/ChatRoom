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

/** Detached authenticated V1 room leave and first-apply local fan-out. */
public final class V1RoomLeaveHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomLeaveUseCase rooms;
    private final LegacyV1RoomAudienceService audience;
    private final V1JsonRoomLeaveCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1RoomLeaveEventSink events;
    private boolean inFlight;

    public V1RoomLeaveHandler(LegacyV1RoomLeaveUseCase rooms,
            LegacyV1RoomAudienceService audience, V1JsonRoomLeaveCodec codec,
            V1AccountConnectionRegistry connections, Executor executor,
            V1RoomLeaveEventSink events) {
        this.rooms = Objects.requireNonNull(rooms, "rooms");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomLeaveCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonRoomLeaveCodec.RequestKind.LEAVE || inFlight) {
            failAndClose(context, false); return;
        }
        Set<UUID> candidates = connections.activeAccountIds();
        inFlight = true;
        try { executor.execute(() -> leave(context, identity, request.roomId(), candidates)); }
        catch (RejectedExecutionException exception) {
            inFlight = false; failAndClose(context, true);
        } catch (RuntimeException exception) {
            inFlight = false; failAndClose(context, false);
        }
    }

    private void leave(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            long roomId, Set<UUID> candidates) {
        long started = System.nanoTime();
        try {
            LegacyV1RoomLeaveResult result = rooms.leave(identity.accountId(), roomId);
            Set<UUID> recipients = Set.of(); boolean routingFailed = false;
            if (result instanceof LegacyV1RoomLeaveResult.Left left
                    && left.newLeave() && !left.dissolved()) {
                try {
                    recipients = audience.activeMappedMembers(
                            left.conversationId(), candidates).stream()
                            .filter(id -> !id.equals(identity.accountId()))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                } catch (RuntimeException exception) { routingFailed = true; }
            }
            Set<UUID> finalRecipients = recipients; boolean finalRoutingFailed = routingFailed;
            schedule(context, () -> complete(context, identity, result, roomId,
                    finalRecipients, finalRoutingFailed,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; failAndClose(context, false); });
        }
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            LegacyV1RoomLeaveResult result, long roomId, Set<UUID> recipients,
            boolean routingFailed, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encodeResponse(result, roomId); }
        catch (RuntimeException exception) { failAndClose(context, false); return; }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));

        V1RoomLeaveEventSink.Outcome outcome; int routed = 0; boolean ownership = false;
        if (result instanceof LegacyV1RoomLeaveResult.Left left) {
            if (!left.newLeave()) outcome = V1RoomLeaveEventSink.Outcome.DUPLICATE;
            else if (left.dissolved()) outcome = V1RoomLeaveEventSink.Outcome.FIRST_DISSOLVED;
            else {
                try {
                    byte[] notification = codec.encodeUserLeft(
                            left.legacyRoomId(), identity.username(), identity.displayName());
                    for (UUID recipient : recipients) if (connections.executeIfActive(recipient,
                            channel -> channel.writeAndFlush(new TextWebSocketFrame(
                                    Unpooled.wrappedBuffer(notification.clone()))))) routed++;
                    if (left.ownershipTransfer().isPresent()) {
                        var transfer = left.ownershipTransfer().orElseThrow();
                        byte[] admin = codec.encodeAdminStatus(left.legacyRoomId());
                        ownership = connections.executeIfActive(transfer.successorAccountId(),
                                channel -> channel.writeAndFlush(new TextWebSocketFrame(
                                        Unpooled.wrappedBuffer(admin))));
                        byte[] system = codec.encodeOwnershipSystemMessage(
                                left.legacyRoomId(), transfer.successorDisplayName());
                        for (UUID recipient : recipients) connections.executeIfActive(recipient,
                                channel -> channel.writeAndFlush(new TextWebSocketFrame(
                                        Unpooled.wrappedBuffer(system.clone()))));
                    }
                } catch (RuntimeException exception) { routingFailed = true; }
                outcome = routed == 0 ? V1RoomLeaveEventSink.Outcome.FIRST_NO_LOCAL_RECIPIENT
                        : V1RoomLeaveEventSink.Outcome.FIRST_ROUTED;
            }
        } else outcome = switch ((LegacyV1RoomLeaveResult.Rejected) result) {
            case INVALID_INPUT -> V1RoomLeaveEventSink.Outcome.INVALID_INPUT;
            case NOT_FOUND -> V1RoomLeaveEventSink.Outcome.NOT_FOUND;
            case NOT_MEMBER -> V1RoomLeaveEventSink.Outcome.NOT_MEMBER;
            case LEAVE_DENIED -> V1RoomLeaveEventSink.Outcome.DENIED;
        };
        if (routingFailed) try { events.failed(); } catch (RuntimeException ignored) { }
        try { events.completed(outcome, routed, ownership, elapsed); }
        catch (RuntimeException ignored) { }
    }

    private void failAndClose(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room leaving unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
