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

/** Detached authenticated administrator-only V1 room rename handler. */
public final class V1RoomRenameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomRenameUseCase rename;
    private final LegacyV1RoomAudienceService audience;
    private final V1JsonRoomRenameCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1RoomRenameEventSink events;
    private boolean inFlight;

    public V1RoomRenameHandler(LegacyV1RoomRenameUseCase rename,
            LegacyV1RoomAudienceService audience, V1JsonRoomRenameCodec codec,
            V1AccountConnectionRegistry connections, Executor executor,
            V1RoomRenameEventSink events) {
        this.rename = Objects.requireNonNull(rename); this.audience = Objects.requireNonNull(audience);
        this.codec = Objects.requireNonNull(codec); this.connections = Objects.requireNonNull(connections);
        this.executor = Objects.requireNonNull(executor); this.events = Objects.requireNonNull(events);
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomRenameCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonRoomRenameCodec.RequestKind.RENAME || inFlight) {
            fail(context, false); return;
        }
        Set<UUID> candidates = connections.activeAccountIds(); inFlight = true;
        try { executor.execute(() -> rename(context, identity, request, candidates)); }
        catch (RejectedExecutionException exception) { inFlight = false; fail(context, true); }
        catch (RuntimeException exception) { inFlight = false; fail(context, false); }
    }

    private void rename(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomRenameCodec.DecodedRequest request, Set<UUID> candidates) {
        long started = System.nanoTime();
        try {
            LegacyV1RoomRenameResult result = rename.rename(new LegacyV1RoomRenameCommand(
                    identity.accountId(), request.roomId(), request.newName()));
            Set<UUID> recipients = result instanceof LegacyV1RoomRenameResult.Renamed renamed
                    && renamed.changed()
                    ? audience.activeMappedMembers(renamed.conversationId(), candidates)
                    : Set.of();
            schedule(context, () -> complete(context, identity, request, result, recipients,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomRenameCodec.DecodedRequest request, LegacyV1RoomRenameResult result,
            Set<UUID> recipients, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        final V1JsonRoomRenameCodec.Notifications notifications;
        try {
            response = codec.encodeResponse(result, request.roomId());
            notifications = result instanceof LegacyV1RoomRenameResult.Renamed renamed
                    && renamed.changed() ? codec.encodeNotifications(
                            renamed, identity.displayName()) : null;
        } catch (RuntimeException exception) { fail(context, false); return; }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
        V1RoomRenameEventSink.Outcome outcome; int routed = 0;
        if (result instanceof LegacyV1RoomRenameResult.Renamed renamed) {
            if (!renamed.changed()) outcome = V1RoomRenameEventSink.Outcome.UNCHANGED;
            else {
                for (UUID recipient : recipients) if (connections.executeIfActive(recipient,
                        channel -> {
                            channel.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(
                                    notifications.renamed().clone())));
                            channel.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(
                                    notifications.systemMessage().clone())));
                        })) routed++;
                outcome = routed == 0 ? V1RoomRenameEventSink.Outcome.CHANGED_NO_LOCAL_RECIPIENT
                        : V1RoomRenameEventSink.Outcome.CHANGED_ROUTED;
            }
        } else outcome = switch ((LegacyV1RoomRenameResult.Rejected) result) {
            case INVALID_INPUT -> V1RoomRenameEventSink.Outcome.INVALID_INPUT;
            case ROOM_ADMIN_REQUIRED -> V1RoomRenameEventSink.Outcome.ADMIN_REQUIRED;
        };
        try { events.completed(outcome, routed, elapsed); } catch (RuntimeException ignored) { }
    }

    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room rename unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
