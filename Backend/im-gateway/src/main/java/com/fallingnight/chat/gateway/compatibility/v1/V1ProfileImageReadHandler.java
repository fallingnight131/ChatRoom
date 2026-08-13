package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.profile.*;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Detached authenticated V1 avatar read adapter over private object storage. */
public final class V1ProfileImageReadHandler
        extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final ProfileImageLoadUseCase images;
    private final V1JsonProfileImageReadCodec codec;
    private final Executor executor;
    private final V1ProfileImageReadEventSink events;
    private boolean inFlight;

    public V1ProfileImageReadHandler(ProfileImageLoadUseCase images,
            V1JsonProfileImageReadCodec codec, Executor executor,
            V1ProfileImageReadEventSink events) {
        this.images = Objects.requireNonNull(images, "images");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonProfileImageReadCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() == V1JsonProfileImageReadCodec.RequestKind.MALFORMED || inFlight) {
            fail(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> load(context, identity, request)); }
        catch (RejectedExecutionException exception) { inFlight = false; fail(context, true); }
        catch (RuntimeException exception) { inFlight = false; fail(context, false); }
    }

    private void load(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonProfileImageReadCodec.DecodedRequest request) {
        long started = System.nanoTime();
        ProfileImageLoadResult result = null;
        try {
            ProfileImageReadTarget target = request.kind()
                    == V1JsonProfileImageReadCodec.RequestKind.ACCOUNT
                    ? new ProfileImageReadTarget.AccountByUsername(
                            identity.accountId(), request.username())
                    : new ProfileImageReadTarget.LegacyRoom(
                            identity.accountId(), request.roomId());
            result = Objects.requireNonNull(images.load(target), "profile image load result");
            ProfileImageLoadResult transferred = result; result = null;
            schedule(context, () -> complete(context, identity, request, transferred,
                    Math.max(0, System.nanoTime() - started)), transferred);
        } catch (RuntimeException exception) {
            close(result);
            schedule(context, () -> { inFlight = false; fail(context, false); }, null);
        }
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonProfileImageReadCodec.DecodedRequest request,
            ProfileImageLoadResult result, long elapsed) {
        inFlight = false;
        try {
            if (!context.channel().isActive() || !identity.equals(context.channel()
                    .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
            byte[] response = codec.encodeResponse(request, result);
            V1ProfileImageReadEventSink.Outcome outcome;
            int byteSize = 0;
            if (result instanceof ProfileImageLoadResult.Loaded loaded) {
                byteSize = loaded.payload().byteSize();
                outcome = request.kind() == V1JsonProfileImageReadCodec.RequestKind.ACCOUNT
                        ? V1ProfileImageReadEventSink.Outcome.ACCOUNT_FOUND
                        : V1ProfileImageReadEventSink.Outcome.ROOM_FOUND;
            } else outcome = result == ProfileImageLoadResult.Missing.INSTANCE
                    ? V1ProfileImageReadEventSink.Outcome.MISSING
                    : V1ProfileImageReadEventSink.Outcome.ACCESS_DENIED;
            completed(outcome, byteSize, elapsed);
            context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
        } catch (RuntimeException exception) {
            fail(context, false);
        } finally { close(result); }
    }

    private void completed(V1ProfileImageReadEventSink.Outcome outcome,
            int byteSize, long elapsed) {
        try { events.completed(outcome, byteSize, elapsed); }
        catch (RuntimeException ignored) { }
    }
    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 profile image unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion,
            ProfileImageLoadResult ownedResult) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) {
            close(ownedResult); context.close();
        }
    }
    private static void close(ProfileImageLoadResult result) {
        if (result instanceof ProfileImageLoadResult.Loaded loaded) loaded.close();
    }
}
