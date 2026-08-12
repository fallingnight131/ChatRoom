package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryQuery;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryUseCase;
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

/** Detached authenticated V1 direct-history reader. */
public final class V1DirectHistoryHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final String FAILURE_REASON = "V1 direct history unavailable";
    private final LegacyV1DirectHistoryUseCase history;
    private final V1JsonDirectHistoryCodec codec;
    private final Executor executor;
    private final V1DirectHistoryEventSink events;
    private boolean inFlight;

    public V1DirectHistoryHandler(LegacyV1DirectHistoryUseCase history,
            V1JsonDirectHistoryCodec codec, Executor executor,
            V1DirectHistoryEventSink events) {
        this.history = Objects.requireNonNull(history, "history");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonDirectHistoryCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonDirectHistoryCodec.RequestKind.HISTORY || inFlight) {
            failAndClose(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> read(context, identity, request)); }
        catch (RejectedExecutionException exception) { inFlight = false; failAndClose(context, true); }
        catch (RuntimeException exception) { inFlight = false; failAndClose(context, false); }
    }

    private void read(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonDirectHistoryCodec.DecodedRequest request) {
        long started = System.nanoTime();
        final LegacyV1DirectHistoryResult result;
        try {
            result = history.read(new LegacyV1DirectHistoryQuery(
                    identity.accountId(), request.targetUsername(), request.limit(),
                    request.beforeEpochMillis(), request.afterSequence()));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; failAndClose(context, false); });
            return;
        }
        schedule(context, () -> complete(context, identity, request, result,
                Math.max(0, System.nanoTime() - started)));
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonDirectHistoryCodec.DecodedRequest request,
            LegacyV1DirectHistoryResult result, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encode(result, request.targetUsername()); }
        catch (RuntimeException exception) { failAndClose(context, false); return; }
        int count = result instanceof LegacyV1DirectHistoryResult.Page page
                ? page.messages().size() : 0;
        V1DirectHistoryEventSink.Outcome outcome = outcome(result);
        try { events.completed(outcome, count, request.afterSequence() != null, elapsed); }
        catch (RuntimeException ignored) { }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }

    private static V1DirectHistoryEventSink.Outcome outcome(
            LegacyV1DirectHistoryResult result) {
        if (result instanceof LegacyV1DirectHistoryResult.Page) {
            return V1DirectHistoryEventSink.Outcome.PAGE;
        }
        return switch ((LegacyV1DirectHistoryResult.Rejected) result) {
            case FRIENDSHIP_ACCESS_DENIED -> V1DirectHistoryEventSink.Outcome.ACCESS_DENIED;
            case INVALID_SEQUENCE_CURSOR -> V1DirectHistoryEventSink.Outcome.INVALID_CURSOR;
            case INVALID_REQUEST -> V1DirectHistoryEventSink.Outcome.INVALID_REQUEST;
        };
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
