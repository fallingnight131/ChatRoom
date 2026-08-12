package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectMessageCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectMessageResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectMessageUseCase;
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

/** Detached V1 direct text/emoji durable acceptance and local live fan-out. */
public final class V1DirectMessageHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final String FAILURE_REASON = "V1 direct messaging unavailable";
    private final LegacyV1DirectMessageUseCase messages;
    private final V1JsonDirectMessageCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1DirectMessageEventSink events;
    private boolean inFlight;

    public V1DirectMessageHandler(LegacyV1DirectMessageUseCase messages,
            V1JsonDirectMessageCodec codec, V1AccountConnectionRegistry connections,
            Executor executor, V1DirectMessageEventSink events) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonDirectMessageCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonDirectMessageCodec.RequestKind.SUBMIT || inFlight) {
            failAndClose(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> submit(context, identity, request)); }
        catch (RejectedExecutionException exception) { inFlight = false; failAndClose(context, true); }
        catch (RuntimeException exception) { inFlight = false; failAndClose(context, false); }
    }

    private void submit(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonDirectMessageCodec.DecodedRequest request) {
        long started = System.nanoTime();
        final LegacyV1DirectMessageResult result;
        try {
            result = messages.submit(new LegacyV1DirectMessageCommand(
                    identity.accountId(), identity.deviceId(), request.targetUsername(),
                    request.clientMessageId(), request.content(), request.contentType()));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; failAndClose(context, false); });
            return;
        }
        schedule(context, () -> complete(context, identity, request, result,
                Math.max(0, System.nanoTime() - started)));
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonDirectMessageCodec.DecodedRequest request,
            LegacyV1DirectMessageResult result, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encodeResponse(result,
                request.targetUsername(), request.clientMessageId()); }
        catch (RuntimeException exception) { failAndClose(context, false); return; }

        V1DirectMessageEventSink.Outcome outcome;
        if (result instanceof LegacyV1DirectMessageResult.Accepted accepted) {
            if (accepted.duplicate()) {
                outcome = V1DirectMessageEventSink.Outcome.DUPLICATE;
            } else {
                final byte[] notification;
                try { notification = codec.encodeNotification(accepted,
                        identity.username(), identity.displayName(), request.clientMessageId(),
                        request.content(), request.contentType()); }
                catch (RuntimeException exception) { failAndClose(context, false); return; }
                context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
                context.writeAndFlush(new TextWebSocketFrame(
                        Unpooled.wrappedBuffer(notification.clone())));
                boolean routed = accepted.targetAccountId().equals(identity.accountId())
                        || connections.executeIfActive(accepted.targetAccountId(), channel ->
                                channel.writeAndFlush(new TextWebSocketFrame(
                                        Unpooled.wrappedBuffer(notification))));
                outcome = routed ? V1DirectMessageEventSink.Outcome.FIRST_ROUTE_SCHEDULED
                        : V1DirectMessageEventSink.Outcome.FIRST_NO_LOCAL_ROUTE;
                completed(outcome, elapsed);
                return;
            }
        } else {
            outcome = switch ((LegacyV1DirectMessageResult.Rejected) result) {
                case FRIENDSHIP_ACCESS_DENIED -> V1DirectMessageEventSink.Outcome.ACCESS_DENIED;
                case INVALID_MESSAGE -> V1DirectMessageEventSink.Outcome.INVALID_MESSAGE;
                case INVALID_CLIENT_MESSAGE_ID ->
                        V1DirectMessageEventSink.Outcome.INVALID_CLIENT_MESSAGE_ID;
                case CLIENT_MESSAGE_ID_CONFLICT -> V1DirectMessageEventSink.Outcome.CONFLICT;
            };
        }
        completed(outcome, elapsed);
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }

    private void completed(V1DirectMessageEventSink.Outcome outcome, long elapsed) {
        try { events.completed(outcome, elapsed); } catch (RuntimeException ignored) { }
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
