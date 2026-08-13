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
import java.util.stream.Collectors;

/** Detached authenticated administrator-only V1 room-file deletion handler. */
public final class V1RoomFileDeletionHandler
        extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomFileDeletionUseCase deletion;
    private final LegacyV1RoomAudienceService audience;
    private final V1JsonRoomFileDeletionCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1RoomFileDeletionEventSink events;
    private boolean inFlight;

    public V1RoomFileDeletionHandler(LegacyV1RoomFileDeletionUseCase deletion,
            LegacyV1RoomAudienceService audience, V1JsonRoomFileDeletionCodec codec,
            V1AccountConnectionRegistry connections, Executor executor,
            V1RoomFileDeletionEventSink events) {
        this.deletion = Objects.requireNonNull(deletion); this.audience = Objects.requireNonNull(audience);
        this.codec = Objects.requireNonNull(codec); this.connections = Objects.requireNonNull(connections);
        this.executor = Objects.requireNonNull(executor); this.events = Objects.requireNonNull(events);
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomFileDeletionCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonRoomFileDeletionCodec.RequestKind.DELETE || inFlight) {
            fail(context, false); return;
        }
        Set<UUID> candidates = connections.activeAccountIds(); inFlight = true;
        try { executor.execute(() -> delete(context, identity, request, candidates)); }
        catch (RejectedExecutionException exception) { inFlight = false; fail(context, true); }
        catch (RuntimeException exception) { inFlight = false; fail(context, false); }
    }

    private void delete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomFileDeletionCodec.DecodedRequest request, Set<UUID> candidates) {
        long started = System.nanoTime();
        try {
            LegacyV1RoomFileDeletionResult result = deletion.delete(
                    new LegacyV1RoomFileDeletionCommand(identity.accountId(), request.roomId(),
                            request.clientOperationId(), request.fileIds()));
            Set<UUID> recipients = result instanceof LegacyV1RoomFileDeletionResult.Deleted deleted
                    && !deleted.duplicate() && !deleted.legacyFileIds().isEmpty()
                    ? audience.activeMappedMembers(deleted.conversationId(), candidates).stream()
                            .filter(id -> !id.equals(identity.accountId()))
                            .collect(Collectors.toUnmodifiableSet()) : Set.of();
            schedule(context, () -> complete(context, identity, request, result, recipients,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomFileDeletionCodec.DecodedRequest request,
            LegacyV1RoomFileDeletionResult result, Set<UUID> recipients, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encodeResponse(result, request.roomId(),
                request.clientOperationId()); }
        catch (RuntimeException exception) { fail(context, false); return; }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
        V1RoomFileDeletionEventSink.Outcome outcome; int routed = 0;
        if (result instanceof LegacyV1RoomFileDeletionResult.Deleted deleted) {
            if (deleted.duplicate()) outcome = V1RoomFileDeletionEventSink.Outcome.DUPLICATE;
            else {
                if (!deleted.legacyFileIds().isEmpty()) {
                    final V1JsonRoomFileDeletionCodec.Notifications notifications;
                    try { notifications = codec.encodeNotifications(deleted,
                            identity.displayName()); }
                    catch (RuntimeException exception) { fail(context, false); return; }
                    for (UUID recipient : recipients) if (connections.executeIfActive(recipient,
                            channel -> {
                                channel.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(
                                        notifications.messagesDeleted().clone())));
                                channel.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(
                                        notifications.roomFiles().clone())));
                            })) routed++;
                }
                outcome = routed == 0
                        ? V1RoomFileDeletionEventSink.Outcome.FIRST_NO_LOCAL_RECIPIENT
                        : V1RoomFileDeletionEventSink.Outcome.FIRST_ROUTED;
            }
        } else outcome = switch ((LegacyV1RoomFileDeletionResult.Rejected) result) {
            case ROOM_ADMIN_REQUIRED -> V1RoomFileDeletionEventSink.Outcome.ADMIN_REQUIRED;
            case INVALID_INPUT -> V1RoomFileDeletionEventSink.Outcome.INVALID_INPUT;
            case CLIENT_OPERATION_ID_CONFLICT ->
                    V1RoomFileDeletionEventSink.Outcome.OPERATION_CONFLICT;
        };
        try { events.completed(outcome, routed, elapsed); } catch (RuntimeException ignored) { }
    }

    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room file deletion unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
