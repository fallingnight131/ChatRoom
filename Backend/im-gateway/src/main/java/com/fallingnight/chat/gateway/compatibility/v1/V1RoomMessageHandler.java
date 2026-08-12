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
/** Detached V1 room durable acceptance and authorized process-local fan-out. */
public final class V1RoomMessageHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final System.Logger LOGGER = System.getLogger(V1RoomMessageHandler.class.getName());
    private final LegacyV1RoomMessageUseCase messages;
    private final LegacyV1RoomAudienceService audience;
    private final V1JsonRoomMessageCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1RoomMessageEventSink events;
    private boolean inFlight;
    public V1RoomMessageHandler(LegacyV1RoomMessageUseCase messages,
            LegacyV1RoomAudienceService audience, V1JsonRoomMessageCodec codec,
            V1AccountConnectionRegistry connections, Executor executor,
            V1RoomMessageEventSink events) {
        this.messages = Objects.requireNonNull(messages); this.audience = Objects.requireNonNull(audience);
        this.codec = Objects.requireNonNull(codec); this.connections = Objects.requireNonNull(connections);
        this.executor = Objects.requireNonNull(executor); this.events = Objects.requireNonNull(events);
    }
    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomMessageCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonRoomMessageCodec.RequestKind.SUBMIT || inFlight) {
            fail(context, false); return;
        }
        Set<UUID> candidates = connections.activeAccountIds();
        inFlight = true;
        try { executor.execute(() -> submit(context, identity, request, candidates)); }
        catch (RejectedExecutionException e) { inFlight = false; fail(context, true); }
        catch (RuntimeException e) { inFlight = false; fail(context, false); }
    }
    private void submit(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomMessageCodec.DecodedRequest request, Set<UUID> candidates) {
        long started = System.nanoTime();
        try {
            LegacyV1RoomMessageResult result = messages.submit(new LegacyV1RoomMessageCommand(
                    identity.accountId(), identity.deviceId(), request.roomId(),
                    request.clientMessageId(), request.content(), request.contentType()));
            Set<UUID> recipients = result instanceof LegacyV1RoomMessageResult.Accepted accepted
                    && !accepted.duplicate()
                    ? audience.activeMappedMembers(accepted.conversationId(), candidates).stream()
                            .filter(id -> !id.equals(identity.accountId())).collect(
                                    java.util.stream.Collectors.toUnmodifiableSet())
                    : Set.of();
            schedule(context, () -> complete(context, identity, request, result, recipients,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "V1 room message processing failed", e);
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }
    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomMessageCodec.DecodedRequest request, LegacyV1RoomMessageResult result,
            Set<UUID> recipients, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encodeResponse(result, request.roomId(), request.clientMessageId()); }
        catch (RuntimeException e) { fail(context, false); return; }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
        V1RoomMessageEventSink.Outcome outcome; int routed = 0;
        if (result instanceof LegacyV1RoomMessageResult.Accepted accepted) {
            if (accepted.duplicate()) outcome = V1RoomMessageEventSink.Outcome.DUPLICATE;
            else {
                final byte[] notification;
                try { notification = codec.encodeNotification(accepted, identity.username(),
                        identity.displayName(), request.clientMessageId(), request.content(),
                        request.contentType()); }
                catch (RuntimeException e) { fail(context, false); return; }
                context.writeAndFlush(new TextWebSocketFrame(
                        Unpooled.wrappedBuffer(notification.clone())));
                for (UUID recipient : recipients) if (connections.executeIfActive(recipient,
                        channel -> channel.writeAndFlush(new TextWebSocketFrame(
                                Unpooled.wrappedBuffer(notification.clone()))))) routed++;
                outcome = routed == 0 ? V1RoomMessageEventSink.Outcome.FIRST_NO_LOCAL_RECIPIENT
                        : V1RoomMessageEventSink.Outcome.FIRST_ROUTED;
            }
        } else outcome = switch ((LegacyV1RoomMessageResult.Rejected) result) {
            case ROOM_ACCESS_DENIED -> V1RoomMessageEventSink.Outcome.ACCESS_DENIED;
            case INVALID_MESSAGE -> V1RoomMessageEventSink.Outcome.INVALID_MESSAGE;
            case INVALID_CLIENT_MESSAGE_ID -> V1RoomMessageEventSink.Outcome.INVALID_CLIENT_MESSAGE_ID;
            case CLIENT_MESSAGE_ID_CONFLICT -> V1RoomMessageEventSink.Outcome.CONFLICT;
        };
        try { events.completed(outcome, routed, elapsed); } catch (RuntimeException ignored) { }
    }
    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); } catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(), "V1 room messaging unavailable"))
                .addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); } catch (RejectedExecutionException e) { context.close(); }
    }
}
