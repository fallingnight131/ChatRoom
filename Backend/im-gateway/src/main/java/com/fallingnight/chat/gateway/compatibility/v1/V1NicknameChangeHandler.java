package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Detached authenticated V1 nickname mutation and per-room effect router. */
public final class V1NicknameChangeHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1NicknameChangeUseCase nicknames;
    private final V1JsonNicknameChangeCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1NicknameChangeEventSink events;
    private boolean inFlight;

    public V1NicknameChangeHandler(LegacyV1NicknameChangeUseCase nicknames,
            V1JsonNicknameChangeCodec codec, V1AccountConnectionRegistry connections,
            Executor executor, V1NicknameChangeEventSink events) {
        this.nicknames = Objects.requireNonNull(nicknames); this.codec = Objects.requireNonNull(codec);
        this.connections = Objects.requireNonNull(connections); this.executor = Objects.requireNonNull(executor);
        this.events = Objects.requireNonNull(events);
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonNicknameChangeCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonNicknameChangeCodec.RequestKind.CHANGE || inFlight) {
            fail(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> execute(context, identity, request.displayName())); }
        catch (RejectedExecutionException exception) { inFlight = false; fail(context, true); }
        catch (RuntimeException exception) { inFlight = false; fail(context, false); }
    }

    private void execute(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            String displayName) {
        long started = System.nanoTime();
        try {
            LegacyV1NicknameChangeResult result = nicknames.change(
                    new LegacyV1NicknameChangeCommand(identity.accountId(), displayName));
            schedule(context, () -> complete(context, identity, result,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            LegacyV1NicknameChangeResult result, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encodeResponse(result); }
        catch (RuntimeException exception) { fail(context, false); return; }
        if (result instanceof LegacyV1NicknameChangeResult.Changed changed)
            context.channel().attr(V1ConnectionAttributes.AUTHENTICATED).set(
                    refreshed(identity, changed.newDisplayName()));
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
        int routed = 0;
        if (result instanceof LegacyV1NicknameChangeResult.Changed changed && changed.changed()) {
            for (LegacyV1NicknameChangeResult.RoomAudience room : changed.roomAudiences()) {
                byte[] notification = codec.encodeNotification(room.legacyRoomId(),
                        identity.username(), changed.newDisplayName());
                for (UUID recipient : room.accountIds()) if (connections.executeIfActive(recipient,
                        channel -> channel.writeAndFlush(new TextWebSocketFrame(
                                Unpooled.wrappedBuffer(notification.clone()))))) routed++;
            }
        }
        completed(outcome(result, routed), routed, elapsed);
    }

    private static LegacyV1AuthenticatedIdentity refreshed(
            LegacyV1AuthenticatedIdentity identity, String displayName) {
        return new LegacyV1AuthenticatedIdentity(identity.legacyUserId(), identity.accountId(),
                identity.deviceId(), identity.sessionId(), identity.expiresAt(),
                identity.username(), displayName, identity.credentialUpgradePending());
    }

    private static V1NicknameChangeEventSink.Outcome outcome(
            LegacyV1NicknameChangeResult result, int routed) {
        if (result instanceof LegacyV1NicknameChangeResult.Changed changed)
            return !changed.changed() ? V1NicknameChangeEventSink.Outcome.UNCHANGED
                    : routed == 0 ? V1NicknameChangeEventSink.Outcome.CHANGED_NO_LOCAL_RECIPIENT
                    : V1NicknameChangeEventSink.Outcome.CHANGED_ROUTED;
        return switch ((LegacyV1NicknameChangeResult.Rejected) result) {
            case INVALID_INPUT -> V1NicknameChangeEventSink.Outcome.INVALID_INPUT;
            case ACCOUNT_UNAVAILABLE -> V1NicknameChangeEventSink.Outcome.ACCOUNT_UNAVAILABLE;
        };
    }
    private void completed(V1NicknameChangeEventSink.Outcome outcome, int routed, long elapsed) {
        try { events.completed(outcome, routed, elapsed); } catch (RuntimeException ignored) { }
    }
    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 nickname change unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
