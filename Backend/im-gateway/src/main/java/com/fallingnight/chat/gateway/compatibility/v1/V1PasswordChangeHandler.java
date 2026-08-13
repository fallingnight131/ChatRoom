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

/** Detached bounded V1 password-change handler with authentication admission. */
public final class V1PasswordChangeHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final LegacyV1PasswordChangeUseCase passwords;
    private final V1JsonPasswordChangeCodec codec;
    private final Executor executor;
    private final AuthenticationAdmissionControl admission;
    private final V1PasswordChangeEventSink events;
    private boolean inFlight;

    public V1PasswordChangeHandler(LegacyV1PasswordChangeUseCase passwords,
            V1JsonPasswordChangeCodec codec, Executor executor,
            AuthenticationAdmissionControl admission, V1PasswordChangeEventSink events) {
        this.passwords = Objects.requireNonNull(passwords); this.codec = Objects.requireNonNull(codec);
        this.executor = Objects.requireNonNull(executor); this.admission = Objects.requireNonNull(admission);
        this.events = Objects.requireNonNull(events);
    }

    @Override protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        LegacyV1AuthenticatedIdentity identity = context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) { context.fireChannelRead(frame.retain()); return; }
        var decoded = codec.decode(ByteBufUtil.getBytes(frame.content()));
        if (decoded.kind() == V1JsonPasswordChangeCodec.RequestKind.OTHER) {
            decoded.close(); context.fireChannelRead(frame.retain()); return;
        }
        if (decoded.kind() == V1JsonPasswordChangeCodec.RequestKind.MALFORMED || inFlight) {
            decoded.close(); fail(context, false); return;
        }
        final AuthenticationAdmissionDecision decision;
        try { decision = Objects.requireNonNull(admission.acquire(
                directPeer(context), identity.username()), "password admission decision"); }
        catch (RuntimeException exception) { decoded.close(); fail(context, false); return; }
        if (!decision.allowed()) {
            decoded.close(); completed(V1PasswordChangeEventSink.Outcome.ADMISSION_DENIED, 0);
            context.writeAndFlush(new TextWebSocketFrame(
                    Unpooled.wrappedBuffer(codec.encodeAdmissionDenied()))); return;
        }
        LegacyV1PasswordChangeCommand command;
        try (decoded) { command = decoded.toCommand(identity.accountId(), identity.sessionId()); }
        inFlight = true;
        try { executor.execute(() -> execute(context, identity, command)); }
        catch (RejectedExecutionException exception) {
            command.close(); inFlight = false; fail(context, true);
        } catch (RuntimeException exception) {
            command.close(); inFlight = false; fail(context, false);
        }
    }

    private void execute(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            LegacyV1PasswordChangeCommand command) {
        long started = System.nanoTime();
        try (command) {
            LegacyV1PasswordChangeResult result = passwords.change(command);
            schedule(context, () -> complete(context, identity, result,
                    Math.max(0, System.nanoTime() - started)));
        } catch (RuntimeException exception) {
            schedule(context, () -> { inFlight = false; fail(context, false); });
        }
    }

    private void complete(ChannelHandlerContext context, LegacyV1AuthenticatedIdentity identity,
            LegacyV1PasswordChangeResult result, long elapsed) {
        inFlight = false;
        if (!context.channel().isActive() || !identity.equals(context.channel()
                .attr(V1ConnectionAttributes.AUTHENTICATED).get())) return;
        if (result instanceof LegacyV1PasswordChangeResult.Changed) {
            try { admission.recordSuccess(identity.username()); } catch (RuntimeException ignored) { }
        }
        final byte[] response;
        try { response = codec.encode(result); }
        catch (RuntimeException exception) { fail(context, false); return; }
        completed(outcome(result), elapsed);
        context.writeAndFlush(new TextWebSocketFrame(Unpooled.wrappedBuffer(response)));
    }

    private static V1PasswordChangeEventSink.Outcome outcome(
            LegacyV1PasswordChangeResult result) {
        if (result instanceof LegacyV1PasswordChangeResult.Changed changed)
            return changed.changed() ? V1PasswordChangeEventSink.Outcome.CHANGED
                    : V1PasswordChangeEventSink.Outcome.ALREADY_CURRENT;
        return switch ((LegacyV1PasswordChangeResult.Rejected) result) {
            case INVALID_INPUT -> V1PasswordChangeEventSink.Outcome.INVALID_INPUT;
            case CURRENT_PASSWORD_INCORRECT ->
                    V1PasswordChangeEventSink.Outcome.CURRENT_PASSWORD_INCORRECT;
            case SESSION_INVALID -> V1PasswordChangeEventSink.Outcome.SESSION_INVALID;
            case CONCURRENT_CHANGE -> V1PasswordChangeEventSink.Outcome.CONCURRENT_CHANGE;
        };
    }
    private void completed(V1PasswordChangeEventSink.Outcome outcome, long elapsed) {
        try { events.completed(outcome, elapsed); } catch (RuntimeException ignored) { }
    }
    private void fail(ChannelHandlerContext context, boolean saturated) {
        try { if (saturated) events.saturated(); else events.failed(); }
        catch (RuntimeException ignored) { }
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(
                WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                "V1 password change unavailable")).addListener(ChannelFutureListener.CLOSE);
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
