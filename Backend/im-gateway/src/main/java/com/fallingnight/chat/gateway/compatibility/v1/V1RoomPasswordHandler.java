package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Detached secret-clearing V1 room-password status and mutation handler. */
public final class V1RoomPasswordHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomPasswordStatusUseCase status;
    private final LegacyV1RoomPasswordUpdateUseCase update;
    private final LegacyV1RoomAudienceService audience;
    private final V1JsonRoomPasswordCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1RoomPasswordEventSink events;
    private boolean inFlight;

    public V1RoomPasswordHandler(LegacyV1RoomPasswordStatusUseCase status,
            LegacyV1RoomPasswordUpdateUseCase update, LegacyV1RoomAudienceService audience,
            V1JsonRoomPasswordCodec codec, V1AccountConnectionRegistry connections,
            Executor executor, V1RoomPasswordEventSink events) {
        this.status = Objects.requireNonNull(status); this.update = Objects.requireNonNull(update);
        this.audience = Objects.requireNonNull(audience); this.codec = Objects.requireNonNull(codec);
        this.connections = Objects.requireNonNull(connections); this.executor = Objects.requireNonNull(executor);
        this.events = Objects.requireNonNull(events);
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomPasswordCodec.RequestKind.OTHER) {
            request.close(); context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() == V1JsonRoomPasswordCodec.RequestKind.MALFORMED || inFlight) {
            request.close(); fail(context, false); return;
        }
        Set<UUID> candidates = connections.activeAccountIds(); inFlight = true;
        try { executor.execute(() -> execute(context, identity, request, candidates)); }
        catch (RejectedExecutionException exception) {
            request.close(); inFlight = false; fail(context, true);
        } catch (RuntimeException exception) {
            request.close(); inFlight = false; fail(context, false);
        }
    }

    private void execute(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonRoomPasswordCodec.DecodedRequest request, Set<UUID> candidates) {
        long started = System.nanoTime(); byte[] password = null;
        try (request) {
            if (request.kind() == V1JsonRoomPasswordCodec.RequestKind.STATUS) {
                var result = status.status(identity.accountId(), request.roomId());
                schedule(context, () -> completeStatus(context, identity, request.roomId(),
                        result, Math.max(0, System.nanoTime() - started)));
                return;
            }
            password = request.passwordCopy();
            try (LegacyV1RoomPasswordCommand command = new LegacyV1RoomPasswordCommand(
                    identity.accountId(), request.roomId(), password)) {
                var result = update.update(command);
                Set<UUID> recipients = result instanceof LegacyV1RoomPasswordUpdateResult.Updated changed
                        && changed.changed()
                        ? audience.activeMappedMembers(changed.conversationId(), candidates)
                        : Set.of();
                schedule(context, () -> completeUpdate(context, identity, request.roomId(),
                        result, recipients, Math.max(0, System.nanoTime() - started)));
            }
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        } finally { if (password != null) Arrays.fill(password, (byte) 0); }
    }

    private void completeStatus(ChannelHandlerContext context,
            LegacyV1AuthenticatedIdentity identity, long roomId,
            LegacyV1RoomPasswordStatusResult result, long elapsed) {
        inFlight = false;
        if (!current(context, identity)) return;
        final byte[] response;
        try { response = codec.encodeStatus(result, roomId); }
        catch (RuntimeException exception) { fail(context, false); return; }
        V1RoomPasswordEventSink.Outcome outcome =
                result instanceof LegacyV1RoomPasswordStatusResult.Authorized
                ? V1RoomPasswordEventSink.Outcome.STATUS_AUTHORIZED
                : switch ((LegacyV1RoomPasswordStatusResult.Rejected) result) {
                    case INVALID_INPUT -> V1RoomPasswordEventSink.Outcome.INVALID_INPUT;
                    case ROOM_ADMIN_REQUIRED -> V1RoomPasswordEventSink.Outcome.ADMIN_REQUIRED;
                };
        completed(outcome, 0, elapsed);
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }

    private void completeUpdate(ChannelHandlerContext context,
            LegacyV1AuthenticatedIdentity identity, long roomId,
            LegacyV1RoomPasswordUpdateResult result, Set<UUID> recipients, long elapsed) {
        inFlight = false;
        if (!current(context, identity)) return;
        final byte[] response; byte[] system = null;
        try {
            response = codec.encodeUpdate(result, roomId);
            if (result instanceof LegacyV1RoomPasswordUpdateResult.Updated updated
                    && updated.changed())
                system = codec.encodeSystemMessage(updated, identity.displayName());
        } catch (RuntimeException exception) { fail(context, false); return; }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
        V1RoomPasswordEventSink.Outcome outcome; int routed = 0;
        if (result instanceof LegacyV1RoomPasswordUpdateResult.Updated updated) {
            if (!updated.changed()) outcome = V1RoomPasswordEventSink.Outcome.SET_UNCHANGED;
            else {
                byte[] notification = system;
                for (UUID recipient : recipients) if (connections.executeIfActive(recipient,
                        channel -> channel.writeAndFlush(new TextWebSocketFrame(
                                Unpooled.wrappedBuffer(notification.clone()))))) routed++;
                outcome = routed == 0
                        ? V1RoomPasswordEventSink.Outcome.SET_CHANGED_NO_LOCAL_RECIPIENT
                        : V1RoomPasswordEventSink.Outcome.SET_CHANGED_ROUTED;
            }
        } else outcome = switch ((LegacyV1RoomPasswordUpdateResult.Rejected) result) {
            case INVALID_INPUT -> V1RoomPasswordEventSink.Outcome.INVALID_INPUT;
            case ROOM_ADMIN_REQUIRED -> V1RoomPasswordEventSink.Outcome.ADMIN_REQUIRED;
        };
        completed(outcome, routed, elapsed);
    }

    private boolean current(ChannelHandlerContext context,
            LegacyV1AuthenticatedIdentity identity) {
        return context.channel().isActive() && identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get());
    }
    private void completed(V1RoomPasswordEventSink.Outcome outcome, int routed, long elapsed) {
        try { events.completed(outcome, routed, elapsed); } catch (RuntimeException ignored) { }
    }
    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room password unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
