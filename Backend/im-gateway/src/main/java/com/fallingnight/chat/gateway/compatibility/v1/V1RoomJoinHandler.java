package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.*;
import com.fallingnight.chat.gateway.transport.*;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Detached authenticated V1 room admission and first-join local fan-out. */
public final class V1RoomJoinHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final System.Logger LOGGER =
            System.getLogger(V1RoomJoinHandler.class.getName());
    private final LegacyV1RoomJoinUseCase rooms;
    private final LegacyV1RoomAudienceService audience;
    private final V1JsonRoomJoinCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final AuthenticationAdmissionControl admission;
    private final V1RoomJoinEventSink events;
    private boolean inFlight;

    public V1RoomJoinHandler(LegacyV1RoomJoinUseCase rooms,
            LegacyV1RoomAudienceService audience, V1JsonRoomJoinCodec codec,
            V1AccountConnectionRegistry connections, Executor executor,
            AuthenticationAdmissionControl admission, V1RoomJoinEventSink events) {
        this.rooms = Objects.requireNonNull(rooms, "rooms");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomJoinCodec.RequestKind.OTHER) {
            request.close(); context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonRoomJoinCodec.RequestKind.JOIN || inFlight) {
            request.close(); failAndClose(context, false); return;
        }
        String limiterKey = limiterKey(request.roomId());
        if (request.hasPassword() && request.roomId() > 0
                && request.roomId() <= Integer.MAX_VALUE) {
            final AuthenticationAdmissionDecision decision;
            try {
                decision = Objects.requireNonNull(admission.acquire(
                        directPeer(context), limiterKey), "room join admission decision");
            } catch (RuntimeException exception) {
                request.close(); failAndClose(context, false); return;
            }
            if (!decision.allowed()) {
                long roomId = request.roomId(); request.close();
                try { events.admissionDenied(decision.dimension()); }
                catch (RuntimeException ignored) { }
                try {
                    context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(
                            codec.encodeRateLimited(roomId, decision.retryAfterMs()))));
                } catch (RuntimeException exception) { failAndClose(context, false); }
                return;
            }
        }
        Set<UUID> candidates = connections.activeAccountIds();
        inFlight = true;
        try { executor.execute(() -> join(context, identity, request, candidates, limiterKey)); }
        catch (RejectedExecutionException exception) {
            request.close(); inFlight = false; failAndClose(context, true);
        } catch (RuntimeException exception) {
            request.close(); inFlight = false; failAndClose(context, false);
        }
    }

    private void join(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomJoinCodec.DecodedRequest request, Set<UUID> candidates,
            String limiterKey) {
        long started = System.nanoTime(); byte[] password = request.passwordCopy();
        boolean suppliedPassword = request.hasPassword();
        try (request; LegacyV1RoomJoinCommand command = new LegacyV1RoomJoinCommand(
                identity.accountId(), request.roomId(), password)) {
            LegacyV1RoomJoinResult result = rooms.join(command);
            Set<UUID> recipients = Set.of(); boolean routingFailed = false;
            if (result instanceof LegacyV1RoomJoinResult.Joined joined && joined.newJoin()) {
                try {
                    recipients = audience.activeMappedMembers(
                            joined.conversationId(), candidates).stream()
                            .filter(id -> !id.equals(identity.accountId()))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                } catch (RuntimeException exception) {
                    routingFailed = true;
                    LOGGER.log(System.Logger.Level.WARNING,
                            "V1 room join audience projection failed", exception);
                }
            }
            Set<UUID> finalRecipients = recipients; boolean finalRoutingFailed = routingFailed;
            schedule(context, () -> complete(context, identity, result, request.roomId(),
                    suppliedPassword, limiterKey, finalRecipients, finalRoutingFailed,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "V1 room join processing failed", exception);
            schedule(context, () -> { inFlight = false; failAndClose(context, false); });
        } finally { if (password != null) Arrays.fill(password, (byte) 0); }
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            LegacyV1RoomJoinResult result, long requestedRoomId, boolean suppliedPassword,
            String limiterKey, Set<UUID> recipients, boolean routingFailed, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encodeResponse(result, requestedRoomId); }
        catch (RuntimeException exception) { failAndClose(context, false); return; }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));

        V1RoomJoinEventSink.Outcome outcome; int routed = 0;
        if (result instanceof LegacyV1RoomJoinResult.Joined joined) {
            if (suppliedPassword) {
                try { admission.recordSuccess(limiterKey); }
                catch (RuntimeException ignored) { }
            }
            if (!joined.newJoin()) outcome = V1RoomJoinEventSink.Outcome.DUPLICATE;
            else {
                try {
                    byte[] notification = codec.encodeNotification(
                            joined, identity.username(), identity.displayName());
                    for (UUID recipient : recipients) if (connections.executeIfActive(recipient,
                            channel -> channel.writeAndFlush(new TextWebSocketFrame(
                                    Unpooled.wrappedBuffer(notification.clone()))))) routed++;
                } catch (RuntimeException exception) {
                    routingFailed = true;
                    LOGGER.log(System.Logger.Level.WARNING,
                            "V1 room join notification encoding/routing failed", exception);
                }
                outcome = routed == 0 ? V1RoomJoinEventSink.Outcome.FIRST_NO_LOCAL_RECIPIENT
                        : V1RoomJoinEventSink.Outcome.FIRST_ROUTED;
            }
        } else outcome = switch ((LegacyV1RoomJoinResult.Rejected) result) {
            case INVALID_INPUT -> V1RoomJoinEventSink.Outcome.INVALID_INPUT;
            case NOT_FOUND -> V1RoomJoinEventSink.Outcome.NOT_FOUND;
            case PASSWORD_REQUIRED -> V1RoomJoinEventSink.Outcome.PASSWORD_REQUIRED;
            case INVALID_PASSWORD -> V1RoomJoinEventSink.Outcome.INVALID_PASSWORD;
            case ROOM_FULL -> V1RoomJoinEventSink.Outcome.ROOM_FULL;
            case JOIN_DENIED -> V1RoomJoinEventSink.Outcome.DENIED;
            case ACCESS_CHANGED -> V1RoomJoinEventSink.Outcome.ACCESS_CHANGED;
        };
        if (routingFailed) try { events.failed(); } catch (RuntimeException ignored) { }
        try { events.completed(outcome, routed, elapsed); } catch (RuntimeException ignored) { }
    }

    private void failAndClose(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room joining unavailable")).addListener(ChannelFutureListener.CLOSE);
    }

    private static String limiterKey(long roomId) { return "room:" + roomId; }
    private static String directPeer(ChannelHandlerContext context) {
        String resolved = context.channel().attr(V2ConnectionAttributes.CLIENT_PEER_ADDRESS).get();
        if (resolved != null && !resolved.isBlank()) return resolved;
        if (context.channel().remoteAddress() instanceof InetSocketAddress remote) {
            return remote.getAddress() == null
                    ? remote.getHostString() : remote.getAddress().getHostAddress();
        }
        return "unknown";
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
