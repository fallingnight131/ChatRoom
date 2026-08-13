package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.*;
import com.fallingnight.chat.gateway.transport.*;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Pre-login bounded V1 registration handler. */
public final class V1RegistrationHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1RegistrationUseCase registration;
    private final V1JsonRegistrationCodec codec;
    private final Executor executor;
    private final AuthenticationAdmissionControl admission;
    private final V1RegistrationEventSink events;
    private boolean inFlight;

    public V1RegistrationHandler(LegacyV1RegistrationUseCase registration,
            V1JsonRegistrationCodec codec, Executor executor,
            AuthenticationAdmissionControl admission, V1RegistrationEventSink events) {
        this.registration = Objects.requireNonNull(registration); this.codec = Objects.requireNonNull(codec);
        this.executor = Objects.requireNonNull(executor); this.admission = Objects.requireNonNull(admission);
        this.events = Objects.requireNonNull(events);
    }
    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        if (context.channel().attr(V1ConnectionAttributes.AUTHENTICATED).get() != null) {
            context.fireChannelRead(frame.retain()); return;
        }
        var decoded = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (decoded.kind() == V1JsonRegistrationCodec.RequestKind.OTHER) {
            decoded.close(); context.fireChannelRead(frame.retain()); return;
        }
        if (decoded.kind() == V1JsonRegistrationCodec.RequestKind.MALFORMED || inFlight) {
            decoded.close(); fail(context, false); return;
        }
        AuthenticationAdmissionDecision decision;
        try { decision = Objects.requireNonNull(admission.acquire(
                directPeer(context), decoded.username()), "registration admission decision"); }
        catch (RuntimeException exception) { decoded.close(); fail(context, false); return; }
        if (!decision.allowed()) {
            decoded.close(); completeMetric(V1RegistrationEventSink.Outcome.ADMISSION_DENIED, 0);
            context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(codec.encode(
                    LegacyV1RegistrationResult.Rejected.REGISTRATION_UNAVAILABLE)))); return;
        }
        String username = decoded.username(); LegacyV1RegistrationCommand command;
        try (decoded) { command = decoded.toCommand(); }
        inFlight = true;
        try { executor.execute(() -> execute(context, username, command)); }
        catch (RejectedExecutionException exception) {
            command.close(); inFlight = false; fail(context, true);
        } catch (RuntimeException exception) { command.close(); inFlight = false; fail(context, false); }
    }
    private void execute(ChannelHandlerContext context, String username,
            LegacyV1RegistrationCommand command) {
        long started = System.nanoTime();
        try (command) {
            LegacyV1RegistrationResult result = registration.register(command);
            schedule(context, () -> complete(context, username, result,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }
    private void complete(ChannelHandlerContext context, String username,
            LegacyV1RegistrationResult result, long elapsed) {
        inFlight = false; if (!context.channel().isActive()) return;
        if (result instanceof LegacyV1RegistrationResult.Registered) {
            try { admission.recordSuccess(username); } catch (RuntimeException ignored) { }
        }
        byte[] response;
        try { response = codec.encode(result); }
        catch (RuntimeException exception) { fail(context, false); return; }
        completeMetric(outcome(result), elapsed);
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }
    private static V1RegistrationEventSink.Outcome outcome(LegacyV1RegistrationResult result) {
        if (result instanceof LegacyV1RegistrationResult.Registered registered)
            return registered.duplicate() ? V1RegistrationEventSink.Outcome.DUPLICATE
                    : V1RegistrationEventSink.Outcome.CREATED;
        return switch ((LegacyV1RegistrationResult.Rejected) result) {
            case INVALID_INPUT -> V1RegistrationEventSink.Outcome.INVALID_INPUT;
            case USERNAME_TAKEN -> V1RegistrationEventSink.Outcome.USERNAME_TAKEN;
            case REGISTRATION_UNAVAILABLE -> V1RegistrationEventSink.Outcome.UNAVAILABLE;
        };
    }
    private void completeMetric(V1RegistrationEventSink.Outcome outcome, long elapsed) {
        try { events.completed(outcome, elapsed); } catch (RuntimeException ignored) { }
    }
    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 registration unavailable")).addListener(ChannelFutureListener.CLOSE);
    }
    private static String directPeer(ChannelHandlerContext context) {
        String resolved = context.channel().attr(V2ConnectionAttributes.CLIENT_PEER_ADDRESS).get();
        if (resolved != null && !resolved.isBlank()) return resolved;
        if (context.channel().remoteAddress() instanceof InetSocketAddress remote)
            return remote.getAddress() == null ? remote.getHostString()
                    : remote.getAddress().getHostAddress();
        return "unknown";
    }
    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try { context.executor().execute(completion); }
        catch (RejectedExecutionException exception) { context.close(); }
    }
}
