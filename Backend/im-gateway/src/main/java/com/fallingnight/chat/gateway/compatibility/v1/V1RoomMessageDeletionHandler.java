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

/** Detached authenticated V1 administrative room-message deletion handler. */
public final class V1RoomMessageDeletionHandler
        extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomMessageDeletionUseCase deletion;
    private final LegacyV1RoomAudienceService audience;
    private final V1JsonRoomMessageDeletionCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1RoomMessageDeletionEventSink events;
    private boolean inFlight;

    public V1RoomMessageDeletionHandler(LegacyV1RoomMessageDeletionUseCase deletion,
            LegacyV1RoomAudienceService audience, V1JsonRoomMessageDeletionCodec codec,
            V1AccountConnectionRegistry connections, Executor executor,
            V1RoomMessageDeletionEventSink events) {
        this.deletion = Objects.requireNonNull(deletion); this.audience = Objects.requireNonNull(audience);
        this.codec = Objects.requireNonNull(codec); this.connections = Objects.requireNonNull(connections);
        this.executor = Objects.requireNonNull(executor); this.events = Objects.requireNonNull(events);
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomMessageDeletionCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonRoomMessageDeletionCodec.RequestKind.DELETE || inFlight) {
            fail(context, false); return;
        }
        Set<UUID> candidates = connections.activeAccountIds(); inFlight = true;
        try { executor.execute(() -> delete(context, identity, request, candidates)); }
        catch (RejectedExecutionException exception) { inFlight = false; fail(context, true); }
        catch (RuntimeException exception) { inFlight = false; fail(context, false); }
    }

    private void delete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomMessageDeletionCodec.DecodedRequest request, Set<UUID> candidates) {
        long started = System.nanoTime();
        try {
            LegacyV1RoomMessageDeletionResult result = deletion.delete(
                    new LegacyV1RoomMessageDeletionCommand(identity.accountId(), request.roomId(),
                            request.clientOperationId(), request.mode(), request.messageIds(),
                            request.cutoffEpochMillis()));
            Set<UUID> recipients = result instanceof LegacyV1RoomMessageDeletionResult.Deleted deleted
                    && !deleted.duplicate()
                    ? audience.activeMappedMembers(deleted.conversationId(), candidates)
                    : Set.of();
            schedule(context, () -> complete(context, identity, request, result, recipients,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomMessageDeletionCodec.DecodedRequest request,
            LegacyV1RoomMessageDeletionResult result, Set<UUID> recipients, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        final V1JsonRoomMessageDeletionCodec.Notifications notifications;
        try {
            response = codec.encodeResponse(result, request.roomId(), request.mode(),
                    request.clientOperationId());
            notifications = result instanceof LegacyV1RoomMessageDeletionResult.Deleted deleted
                    && !deleted.duplicate()
                    ? codec.encodeNotifications(deleted, identity.displayName()) : null;
        } catch (RuntimeException exception) { fail(context, false); return; }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
        V1RoomMessageDeletionEventSink.Outcome outcome; int routed = 0;
        if (result instanceof LegacyV1RoomMessageDeletionResult.Deleted deleted) {
            if (deleted.duplicate()) outcome = V1RoomMessageDeletionEventSink.Outcome.DUPLICATE;
            else {
                for (UUID recipient : recipients) if (connections.executeIfActive(recipient,
                        channel -> {
                            if (!recipient.equals(identity.accountId())) {
                                channel.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(
                                        notifications.messagesDeleted().clone())));
                            }
                            channel.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(
                                    notifications.systemMessage().clone())));
                        })) routed++;
                outcome = routed == 0
                        ? V1RoomMessageDeletionEventSink.Outcome.FIRST_NO_LOCAL_RECIPIENT
                        : V1RoomMessageDeletionEventSink.Outcome.FIRST_ROUTED;
            }
        } else outcome = switch ((LegacyV1RoomMessageDeletionResult.Rejected) result) {
            case ROOM_ADMIN_REQUIRED -> V1RoomMessageDeletionEventSink.Outcome.ADMIN_REQUIRED;
            case INVALID_INPUT -> V1RoomMessageDeletionEventSink.Outcome.INVALID_INPUT;
            case CLIENT_OPERATION_ID_CONFLICT ->
                    V1RoomMessageDeletionEventSink.Outcome.OPERATION_CONFLICT;
            case DELETE_SCOPE_TOO_LARGE -> V1RoomMessageDeletionEventSink.Outcome.SCOPE_TOO_LARGE;
        };
        try { events.completed(outcome, routed, elapsed); } catch (RuntimeException ignored) { }
    }

    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room message deletion unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
