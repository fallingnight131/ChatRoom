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

/** Detached owner-bound V1 room recall and first-only local notification. */
public final class V1RoomRecallHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomRecallUseCase recalls;
    private final LegacyV1RoomAudienceService audience;
    private final V1JsonRoomRecallCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1RoomRecallEventSink events;
    private boolean inFlight;
    public V1RoomRecallHandler(LegacyV1RoomRecallUseCase recalls,
            LegacyV1RoomAudienceService audience, V1JsonRoomRecallCodec codec,
            V1AccountConnectionRegistry connections, Executor executor,
            V1RoomRecallEventSink events) {
        this.recalls = Objects.requireNonNull(recalls); this.audience = Objects.requireNonNull(audience);
        this.codec = Objects.requireNonNull(codec); this.connections = Objects.requireNonNull(connections);
        this.executor = Objects.requireNonNull(executor); this.events = Objects.requireNonNull(events);
    }
    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomRecallCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonRoomRecallCodec.RequestKind.RECALL || inFlight) {
            fail(context, false); return;
        }
        Set<UUID> candidates = connections.activeAccountIds(); inFlight = true;
        try { executor.execute(() -> recall(context, identity, request, candidates)); }
        catch (RejectedExecutionException e) { inFlight = false; fail(context, true); }
        catch (RuntimeException e) { inFlight = false; fail(context, false); }
    }
    private void recall(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomRecallCodec.DecodedRequest request, Set<UUID> candidates) {
        long started = System.nanoTime();
        try {
            LegacyV1RoomRecallResult result = recalls.recall(new LegacyV1RoomRecallCommand(
                    identity.accountId(), request.legacyRoomId(), request.legacyMessageId()));
            Set<UUID> recipients = result instanceof LegacyV1RoomRecallResult.Recalled recalled
                    && !recalled.duplicate()
                    ? audience.activeMappedMembers(recalled.conversationId(), candidates).stream()
                            .filter(id -> !id.equals(identity.accountId()))
                            .collect(Collectors.toUnmodifiableSet()) : Set.of();
            schedule(context, () -> complete(context, identity, request, result, recipients,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException e) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }
    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomRecallCodec.DecodedRequest request, LegacyV1RoomRecallResult result,
            Set<UUID> recipients, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encodeResponse(result, request.legacyRoomId(),
                request.legacyMessageId()); }
        catch (RuntimeException e) { fail(context, false); return; }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
        V1RoomRecallEventSink.Outcome outcome; int routed = 0;
        if (result instanceof LegacyV1RoomRecallResult.Recalled recalled) {
            if (recalled.duplicate()) outcome = V1RoomRecallEventSink.Outcome.DUPLICATE;
            else {
                final byte[] notification;
                try { notification = codec.encodeNotification(recalled, identity.username()); }
                catch (RuntimeException e) { fail(context, false); return; }
                context.writeAndFlush(new TextWebSocketFrame(
                        Unpooled.wrappedBuffer(notification.clone())));
                for (UUID recipient : recipients) if (connections.executeIfActive(recipient,
                        channel -> channel.writeAndFlush(new TextWebSocketFrame(
                                Unpooled.wrappedBuffer(notification.clone()))))) routed++;
                outcome = routed == 0 ? V1RoomRecallEventSink.Outcome.FIRST_NO_LOCAL_RECIPIENT
                        : V1RoomRecallEventSink.Outcome.FIRST_ROUTED;
            }
        } else outcome = switch ((LegacyV1RoomRecallResult.Rejected) result) {
            case ROOM_ACCESS_DENIED -> V1RoomRecallEventSink.Outcome.ACCESS_DENIED;
            case RECALL_REJECTED -> V1RoomRecallEventSink.Outcome.RECALL_REJECTED;
            case INVALID_REQUEST -> V1RoomRecallEventSink.Outcome.INVALID_REQUEST;
        };
        try { events.completed(outcome, routed, elapsed); } catch (RuntimeException ignored) { }
    }
    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room recall unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
