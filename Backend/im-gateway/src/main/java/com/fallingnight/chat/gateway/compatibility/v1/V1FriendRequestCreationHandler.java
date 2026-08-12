package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestCreationResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestCreationUseCase;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Detached authenticated V1 friend-request creation and first notification. */
public final class V1FriendRequestCreationHandler
        extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final String FAILURE_REASON = "V1 friend request unavailable";
    private final LegacyV1FriendRequestCreationUseCase creation;
    private final V1JsonFriendRequestCreationCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1FriendRequestCreationEventSink events;
    private boolean inFlight;

    public V1FriendRequestCreationHandler(
            LegacyV1FriendRequestCreationUseCase creation,
            V1JsonFriendRequestCreationCodec codec,
            V1AccountConnectionRegistry connections,
            Executor executor,
            V1FriendRequestCreationEventSink events) {
        this.creation = Objects.requireNonNull(creation, "creation");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonFriendRequestCreationCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonFriendRequestCreationCodec.RequestKind.CREATE || inFlight) {
            failAndClose(context, false); return;
        }
        inFlight = true;
        try {
            executor.execute(() -> createOffLoop(context, identity, request.username()));
        } catch (RejectedExecutionException exception) {
            inFlight = false; failAndClose(context, true);
        } catch (RuntimeException exception) {
            inFlight = false; failAndClose(context, false);
        }
    }

    private void createOffLoop(
            ChannelHandlerContext context,
            LegacyV1AuthenticatedIdentity identity,
            String targetUsername) {
        long started = System.nanoTime();
        final LegacyV1FriendRequestCreationResult result;
        try { result = creation.create(identity.accountId(), targetUsername); }
        catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; failAndClose(context, false); });
            return;
        }
        schedule(context, () -> complete(context, identity, result,
                Math.max(0, System.nanoTime() - started)));
    }

    private void complete(
            ChannelHandlerContext context,
            LegacyV1AuthenticatedIdentity identity,
            LegacyV1FriendRequestCreationResult result,
            long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        byte[] response;
        try { response = codec.encodeResponse(result); }
        catch (RuntimeException exception) { failAndClose(context, false); return; }

        V1FriendRequestCreationEventSink.Outcome outcome;
        if (result instanceof LegacyV1FriendRequestCreationResult.Accepted accepted) {
            if (accepted.duplicate()) {
                outcome = V1FriendRequestCreationEventSink.Outcome.DUPLICATE;
            } else {
                final byte[] notification;
                try {
                    notification = codec.encodeNotification(
                            identity.username(), identity.displayName());
                } catch (RuntimeException exception) {
                    failAndClose(context, false); return;
                }
                boolean scheduled = connections.executeIfActive(
                        accepted.recipientAccountId(), channel -> channel.writeAndFlush(
                                new TextWebSocketFrame(Unpooled.wrappedBuffer(notification))));
                outcome = scheduled
                        ? V1FriendRequestCreationEventSink.Outcome.FIRST_ROUTE_SCHEDULED
                        : V1FriendRequestCreationEventSink.Outcome.FIRST_NO_LOCAL_ROUTE;
            }
        } else {
            outcome = switch ((LegacyV1FriendRequestCreationResult.Rejected) result) {
                case USER_NOT_FOUND -> V1FriendRequestCreationEventSink.Outcome.USER_NOT_FOUND;
                case SELF_REQUEST -> V1FriendRequestCreationEventSink.Outcome.SELF_REQUEST;
                case ALREADY_FRIENDS -> V1FriendRequestCreationEventSink.Outcome.ALREADY_FRIENDS;
                case REVERSE_PENDING -> V1FriendRequestCreationEventSink.Outcome.REVERSE_PENDING;
                case INVALID_TARGET -> V1FriendRequestCreationEventSink.Outcome.INVALID_TARGET;
            };
        }
        try { events.completed(outcome, elapsed); } catch (RuntimeException ignored) { }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }

    private void failAndClose(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (!context.channel().isActive()) return;
        context.writeAndFlush(new CloseWebSocketFrame(
                        WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(), FAILURE_REASON))
                .addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
