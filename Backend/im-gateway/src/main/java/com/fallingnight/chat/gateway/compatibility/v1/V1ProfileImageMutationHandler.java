package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.profile.*;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Detached authenticated V1 avatar mutation and first-commit effect router. */
public final class V1ProfileImageMutationHandler
        extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final ProfileImageMutationUseCase images;
    private final V1JsonProfileImageMutationCodec codec;
    private final V1AccountConnectionRegistry connections;
    private final Executor executor;
    private final V1ProfileImageMutationEventSink events;
    private boolean inFlight;

    public V1ProfileImageMutationHandler(ProfileImageMutationUseCase images,
            V1JsonProfileImageMutationCodec codec, V1AccountConnectionRegistry connections,
            Executor executor, V1ProfileImageMutationEventSink events) {
        this.images = Objects.requireNonNull(images); this.codec = Objects.requireNonNull(codec);
        this.connections = Objects.requireNonNull(connections);
        this.executor = Objects.requireNonNull(executor); this.events = Objects.requireNonNull(events);
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonProfileImageMutationCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() == V1JsonProfileImageMutationCodec.RequestKind.MALFORMED || inFlight) {
            request.close(); fail(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> mutate(context, identity, request)); }
        catch (RejectedExecutionException exception) {
            request.close(); inFlight = false; fail(context, true);
        } catch (RuntimeException exception) {
            request.close(); inFlight = false; fail(context, false);
        }
    }

    private void mutate(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonProfileImageMutationCodec.DecodedRequest request) {
        long started = System.nanoTime(); ProfileImageMutationResult result = null;
        try {
            int byteSize = request.upload().byteSize();
            ProfileImageTarget target = request.kind()
                    == V1JsonProfileImageMutationCodec.RequestKind.ACCOUNT
                    ? new ProfileImageTarget.Account(identity.accountId())
                    : new ProfileImageTarget.LegacyRoom(identity.accountId(), request.roomId());
            result = Objects.requireNonNull(images.change(target, request.upload()));
            ProfileImageMutationResult transferred = result; result = null;
            schedule(context, () -> complete(context, identity, request, transferred,
                    byteSize, Math.max(0, System.nanoTime() - started)), transferred);
        } catch (RuntimeException exception) {
            close(result); request.close();
            schedule(context, () -> { inFlight = false; fail(context, false); }, null);
        }
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            V1JsonProfileImageMutationCodec.DecodedRequest request,
            ProfileImageMutationResult result, int byteSize, long elapsed) {
        inFlight = false;
        try {
            if (!context.channel().isActive() || !identity.equals(context.channel()
                    .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
            byte[] response = codec.encodeResponse(request, result);
            context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
            int routed = 0;
            if (result instanceof ProfileImageMutationResult.Committed committed
                    && committed.metadata().changed()) {
                byte[] notification = codec.encodeNotification(
                        request, identity.username(), committed);
                for (UUID recipient : recipients(request, identity, committed))
                    if (connections.executeIfActive(recipient, channel -> channel.writeAndFlush(
                            new TextWebSocketFrame(Unpooled.wrappedBuffer(notification.clone())))))
                        routed++;
            }
            completed(outcome(result, routed), routed, byteSize, elapsed);
        } catch (RuntimeException exception) { fail(context, false); }
        finally { close(result); request.close(); }
    }

    private Set<UUID> recipients(V1JsonProfileImageMutationCodec.DecodedRequest request,
            LegacyV1AuthenticatedIdentity identity,
            ProfileImageMutationResult.Committed committed) {
        Set<UUID> recipients = request.kind()
                == V1JsonProfileImageMutationCodec.RequestKind.ACCOUNT
                ? new HashSet<>(connections.activeAccountIds())
                : new HashSet<>(committed.metadata().roomPeerAccountIds());
        recipients.remove(identity.accountId()); return Set.copyOf(recipients);
    }

    private static V1ProfileImageMutationEventSink.Outcome outcome(
            ProfileImageMutationResult result, int routed) {
        if (result instanceof ProfileImageMutationResult.Committed committed)
            return !committed.metadata().changed()
                    ? V1ProfileImageMutationEventSink.Outcome.UNCHANGED
                    : routed == 0
                        ? V1ProfileImageMutationEventSink.Outcome.CHANGED_NO_LOCAL_RECIPIENT
                        : V1ProfileImageMutationEventSink.Outcome.CHANGED_ROUTED;
        return switch ((ProfileImageMutationResult.Rejected) result) {
            case INVALID_IMAGE -> V1ProfileImageMutationEventSink.Outcome.INVALID_IMAGE;
            case ACCOUNT_UNAVAILABLE -> V1ProfileImageMutationEventSink.Outcome.ACCOUNT_UNAVAILABLE;
            case ROOM_ADMIN_REQUIRED -> V1ProfileImageMutationEventSink.Outcome.ROOM_ADMIN_REQUIRED;
            case OBJECT_EVIDENCE_CONFLICT ->
                    V1ProfileImageMutationEventSink.Outcome.OBJECT_EVIDENCE_CONFLICT;
        };
    }

    private void completed(V1ProfileImageMutationEventSink.Outcome outcome,
            int routed, int byteSize, long elapsed) {
        try { events.completed(outcome, routed, byteSize, elapsed); }
        catch (RuntimeException ignored) { }
    }
    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 profile image mutation unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion,
            ProfileImageMutationResult ownedResult) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { close(ownedResult); context.close(); }
    }
    private static void close(ProfileImageMutationResult result) {
        if (result instanceof ProfileImageMutationResult.Committed committed) committed.close();
    }
}
