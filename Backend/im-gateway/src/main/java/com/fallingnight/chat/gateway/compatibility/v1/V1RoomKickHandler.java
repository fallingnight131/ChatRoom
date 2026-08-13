package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/** Detached authenticated V1 kick command and first-commit local fan-out. */
public final class V1RoomKickHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomKickUseCase kicks;
    private final LegacyV1RoomAudienceService audience;
    private final V1JsonRoomKickCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1RoomKickEventSink events;
    private boolean inFlight;

    public V1RoomKickHandler(LegacyV1RoomKickUseCase kicks,
            LegacyV1RoomAudienceService audience, V1JsonRoomKickCodec codec,
            V1AccountConnectionRegistry connections, Executor executor,
            V1RoomKickEventSink events) {
        this.kicks = Objects.requireNonNull(kicks); this.audience = Objects.requireNonNull(audience);
        this.codec = Objects.requireNonNull(codec); this.connections = Objects.requireNonNull(connections);
        this.executor = Objects.requireNonNull(executor); this.events = Objects.requireNonNull(events);
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        var identity = context.channel().attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomKickCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonRoomKickCodec.RequestKind.KICK || inFlight) {
            fail(context, false); return;
        }
        Set<UUID> candidates = connections.activeAccountIds(); inFlight = true;
        try { executor.execute(() -> execute(context, identity, request, candidates)); }
        catch (RejectedExecutionException exception) { inFlight = false; fail(context, true); }
        catch (RuntimeException exception) { inFlight = false; fail(context, false); }
    }

    private void execute(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomKickCodec.DecodedRequest request, Set<UUID> candidates) {
        long started = System.nanoTime();
        try {
            LegacyV1RoomKickResult result = kicks.kick(new LegacyV1RoomKickCommand(
                    identity.accountId(), request.roomId(), request.targetUsername()));
            Set<UUID> recipients = Set.of(); boolean routingFailed = false;
            if (result instanceof LegacyV1RoomKickResult.Kicked kicked && kicked.changed()) {
                try {
                    recipients = audience.activeMappedMembers(
                            kicked.conversationId(), candidates).stream()
                            .filter(id -> !id.equals(identity.accountId()))
                            .filter(id -> !id.equals(kicked.targetAccountId()))
                            .collect(Collectors.toUnmodifiableSet());
                } catch (RuntimeException exception) { routingFailed = true; }
            }
            Set<UUID> finalRecipients = recipients; boolean finalRoutingFailed = routingFailed;
            schedule(context, () -> complete(context, identity, request, result,
                    finalRecipients, finalRoutingFailed,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomKickCodec.DecodedRequest request, LegacyV1RoomKickResult result,
            Set<UUID> recipients, boolean routingFailed, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        try {
            context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(
                    codec.response(result, request.roomId(), request.targetUsername()))));
        } catch (RuntimeException exception) { fail(context, false); return; }

        int routed = 0; boolean targetRouted = false; V1RoomKickEventSink.Outcome outcome;
        if (result instanceof LegacyV1RoomKickResult.Kicked kicked) {
            if (!kicked.changed()) outcome = V1RoomKickEventSink.Outcome.DUPLICATE;
            else {
                try {
                    byte[] target = codec.targetNotification(kicked, identity.displayName());
                    targetRouted = connections.executeIfActive(kicked.targetAccountId(),
                            channel -> channel.writeAndFlush(new TextWebSocketFrame(
                                    Unpooled.wrappedBuffer(target))));
                    var effects = codec.remainingMemberEffects(kicked, identity.displayName());
                    for (UUID recipient : recipients) if (connections.executeIfActive(recipient,
                            channel -> {
                                channel.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(
                                        effects.userLeft().clone())));
                                channel.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(
                                        effects.systemMessage().clone())));
                            })) routed++;
                } catch (RuntimeException exception) { routingFailed = true; }
                outcome = routed == 0 && !targetRouted
                        ? V1RoomKickEventSink.Outcome.FIRST_NO_LOCAL_RECIPIENT
                        : V1RoomKickEventSink.Outcome.FIRST_ROUTED;
            }
        } else outcome = V1RoomKickEventSink.Outcome.REJECTED;
        if (routingFailed) try { events.failed(); } catch (RuntimeException ignored) { }
        try { events.completed(outcome, routed, targetRouted, elapsed); }
        catch (RuntimeException ignored) { }
    }

    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room kick unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); } catch (RejectedExecutionException e) { context.close(); }
    }
}
