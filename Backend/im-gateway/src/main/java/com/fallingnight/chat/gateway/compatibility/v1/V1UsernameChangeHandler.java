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

/** Detached authenticated V1 login-name mutation and peer-room effect router. */
public final class V1UsernameChangeHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1UsernameChangeUseCase usernames;
    private final V1JsonUsernameChangeCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1UsernameChangeEventSink events;
    private boolean inFlight;

    public V1UsernameChangeHandler(LegacyV1UsernameChangeUseCase usernames,
            V1JsonUsernameChangeCodec codec, V1AccountConnectionRegistry connections,
            Executor executor, V1UsernameChangeEventSink events) {
        this.usernames = Objects.requireNonNull(usernames); this.codec = Objects.requireNonNull(codec);
        this.connections = Objects.requireNonNull(connections); this.executor = Objects.requireNonNull(executor);
        this.events = Objects.requireNonNull(events);
    }
    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonUsernameChangeCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonUsernameChangeCodec.RequestKind.CHANGE || inFlight) {
            fail(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> execute(context, identity, request.newUsername())); }
        catch (RejectedExecutionException exception) { inFlight = false; fail(context, true); }
        catch (RuntimeException exception) { inFlight = false; fail(context, false); }
    }
    private void execute(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            String username) {
        long started = System.nanoTime();
        try {
            LegacyV1UsernameChangeResult result = usernames.change(
                    new LegacyV1UsernameChangeCommand(identity.accountId(), username));
            schedule(context, () -> complete(context, identity, result,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }
    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            LegacyV1UsernameChangeResult result, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        byte[] response;
        try { response = codec.encodeResponse(result); }
        catch (RuntimeException exception) { fail(context, false); return; }
        if (result instanceof LegacyV1UsernameChangeResult.Changed changed)
            context.channel().attr(V1ConnectionAttributes.AUTHENTICATED).set(
                    refreshed(identity, changed.newUsername()));
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
        int routed = 0;
        if (result instanceof LegacyV1UsernameChangeResult.Changed changed && changed.changed()) {
            for (LegacyV1UsernameChangeResult.RoomAudience room : changed.roomAudiences()) {
                byte[] notification = codec.encodeNotification(
                        room.legacyRoomId(), changed, identity.displayName());
                for (UUID recipient : room.peerAccountIds()) if (connections.executeIfActive(recipient,
                        channel -> channel.writeAndFlush(new TextWebSocketFrame(
                                Unpooled.wrappedBuffer(notification.clone()))))) routed++;
            }
        }
        try { events.completed(outcome(result, routed), routed, elapsed); }
        catch (RuntimeException ignored) { }
    }
    private static LegacyV1AuthenticatedIdentity refreshed(
            LegacyV1AuthenticatedIdentity identity, String username) {
        return new LegacyV1AuthenticatedIdentity(identity.legacyUserId(), identity.accountId(),
                identity.deviceId(), identity.sessionId(), identity.expiresAt(), username,
                identity.displayName(), identity.credentialUpgradePending());
    }
    private static V1UsernameChangeEventSink.Outcome outcome(
            LegacyV1UsernameChangeResult result, int routed) {
        if (result instanceof LegacyV1UsernameChangeResult.Changed changed)
            return !changed.changed() ? V1UsernameChangeEventSink.Outcome.UNCHANGED
                    : routed == 0 ? V1UsernameChangeEventSink.Outcome.CHANGED_NO_LOCAL_RECIPIENT
                    : V1UsernameChangeEventSink.Outcome.CHANGED_ROUTED;
        if (result instanceof LegacyV1UsernameChangeResult.Cooldown)
            return V1UsernameChangeEventSink.Outcome.COOLDOWN;
        return switch ((LegacyV1UsernameChangeResult.Rejected) result) {
            case INVALID_INPUT -> V1UsernameChangeEventSink.Outcome.INVALID_INPUT;
            case SAME_AS_CURRENT -> V1UsernameChangeEventSink.Outcome.SAME_AS_CURRENT;
            case USERNAME_TAKEN -> V1UsernameChangeEventSink.Outcome.USERNAME_TAKEN;
            case ACCOUNT_UNAVAILABLE -> V1UsernameChangeEventSink.Outcome.ACCOUNT_UNAVAILABLE;
        };
    }
    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 username change unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
