package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectRecallCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectRecallResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectRecallUseCase;
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

/** Detached owner-bound V1 direct recall and first-only local notification. */
public final class V1DirectRecallHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1DirectRecallUseCase recalls;
    private final V1JsonDirectRecallCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1DirectRecallEventSink events;
    private boolean inFlight;
    public V1DirectRecallHandler(LegacyV1DirectRecallUseCase recalls,
            V1JsonDirectRecallCodec codec, V1AccountConnectionRegistry connections,
            Executor executor, V1DirectRecallEventSink events) {
        this.recalls = Objects.requireNonNull(recalls, "recalls");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }
    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        var identity = context.channel().attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonDirectRecallCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonDirectRecallCodec.RequestKind.RECALL || inFlight) {
            fail(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> recall(context, identity, request)); }
        catch (RejectedExecutionException e) { inFlight = false; fail(context, true); }
        catch (RuntimeException e) { inFlight = false; fail(context, false); }
    }
    private void recall(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonDirectRecallCodec.DecodedRequest request) {
        long started = System.nanoTime(); final LegacyV1DirectRecallResult result;
        try { result = recalls.recall(new LegacyV1DirectRecallCommand(
                identity.accountId(), request.legacyMessageId())); }
        catch (RuntimeException e) {
            schedule(context, () -> { inFlight = false; fail(context, false); }); return;
        }
        schedule(context, () -> complete(context, identity, request, result,
                Math.max(0, System.nanoTime() - started)));
    }
    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonDirectRecallCodec.DecodedRequest request,
            LegacyV1DirectRecallResult result, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encodeResponse(result, request.legacyMessageId()); }
        catch (RuntimeException e) { fail(context, false); return; }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
        V1DirectRecallEventSink.Outcome outcome;
        if (result instanceof LegacyV1DirectRecallResult.Recalled recalled) {
            if (recalled.duplicate()) outcome = V1DirectRecallEventSink.Outcome.DUPLICATE;
            else {
                final byte[] notification;
                try { notification = codec.encodeNotification(recalled, identity.username()); }
                catch (RuntimeException e) { fail(context, false); return; }
                boolean routed = !recalled.targetAccountId().equals(identity.accountId())
                        && connections.executeIfActive(recalled.targetAccountId(), channel ->
                        channel.writeAndFlush(new TextWebSocketFrame(
                                Unpooled.wrappedBuffer(notification))));
                outcome = routed ? V1DirectRecallEventSink.Outcome.FIRST_ROUTE_SCHEDULED
                        : V1DirectRecallEventSink.Outcome.FIRST_NO_LOCAL_ROUTE;
            }
        } else outcome = result == LegacyV1DirectRecallResult.Rejected.INVALID_MESSAGE_ID
                ? V1DirectRecallEventSink.Outcome.INVALID_ID
                : V1DirectRecallEventSink.Outcome.DENIED;
        try { events.completed(outcome, elapsed); } catch (RuntimeException ignored) { }
    }
    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 direct recall unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
