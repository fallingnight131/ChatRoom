package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.*;
import java.util.concurrent.*;

/** Detached authenticated read-only V1 room-settings handler. */
public final class V1RoomSettingsHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RoomSettingsUseCase settings;
    private final V1JsonRoomSettingsCodec codec;
    private final Executor executor;
    private final V1RoomSettingsEventSink events;
    private boolean inFlight;
    public V1RoomSettingsHandler(LegacyV1RoomSettingsUseCase settings,
            V1JsonRoomSettingsCodec codec, Executor executor, V1RoomSettingsEventSink events) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }
    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var request = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (request.kind() == V1JsonRoomSettingsCodec.RequestKind.OTHER) {
            context.fireChannelRead(frame.retain()); return;
        }
        if (request.kind() != V1JsonRoomSettingsCodec.RequestKind.READ || inFlight) {
            failAndClose(context, false); return;
        }
        inFlight = true;
        try { executor.execute(() -> read(context, identity, request.roomId())); }
        catch (RejectedExecutionException exception) {
            inFlight = false; failAndClose(context, true);
        } catch (RuntimeException exception) {
            inFlight = false; failAndClose(context, false);
        }
    }
    private void read(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            long roomId) {
        long started = System.nanoTime();
        try {
            var result = Objects.requireNonNull(settings.read(identity.accountId(), roomId));
            schedule(context, () -> complete(context, identity, result, roomId,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; failAndClose(context, false); });
        }
    }
    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            LegacyV1RoomSettingsResult result, long roomId, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        final byte[] response;
        try { response = codec.encode(result, roomId); }
        catch (RuntimeException exception) { failAndClose(context, false); return; }
        V1RoomSettingsEventSink.Outcome outcome =
                result instanceof LegacyV1RoomSettingsResult.Read
                        ? V1RoomSettingsEventSink.Outcome.READ
                        : switch ((LegacyV1RoomSettingsResult.Rejected) result) {
                            case INVALID_INPUT -> V1RoomSettingsEventSink.Outcome.INVALID_INPUT;
                            case ROOM_ACCESS_DENIED -> V1RoomSettingsEventSink.Outcome.ACCESS_DENIED;
                        };
        try { events.completed(outcome, elapsed); } catch (RuntimeException ignored) { }
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }
    private void failAndClose(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 room settings unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
