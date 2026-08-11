package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1LoginResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1LoginUseCase;
import com.fallingnight.chat.application.identity.AuthenticateCommand;
import com.fallingnight.chat.application.identity.ClientDescriptor;
import com.fallingnight.chat.application.identity.ClientPlatform;
import com.fallingnight.chat.gateway.transport.AuthenticationAdmissionControl;
import com.fallingnight.chat.gateway.transport.AuthenticationAdmissionDecision;
import com.fallingnight.chat.gateway.transport.AuthenticationEventSink;
import com.fallingnight.chat.gateway.transport.AuthenticationOutcome;
import com.fallingnight.chat.gateway.transport.V2ConnectionAttributes;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** One-attempt V1 Web login adapter; no listener installs it yet. */
public final class V1WebLoginHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    public static final String COMPATIBILITY_DEVICE_ID = "legacy-v1-web";
    private static final ClientDescriptor CLIENT = new ClientDescriptor(
            COMPATIBILITY_DEVICE_ID, ClientPlatform.WEB, "v1");

    private enum State {
        EXPECTING_LOGIN,
        AUTHENTICATING,
        AUTHENTICATED,
        TERMINAL
    }

    private final LegacyV1LoginUseCase login;
    private final V1JsonLoginCodec codec;
    private final Executor authenticationExecutor;
    private final AuthenticationAdmissionControl admission;
    private final AuthenticationEventSink events;
    private State state = State.EXPECTING_LOGIN;

    public V1WebLoginHandler(
            LegacyV1LoginUseCase login,
            V1JsonLoginCodec codec,
            Executor authenticationExecutor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events) {
        this.login = Objects.requireNonNull(login, "login");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.authenticationExecutor = Objects.requireNonNull(
                authenticationExecutor, "authenticationExecutor");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        if (state == State.AUTHENTICATED) {
            context.fireChannelRead(frame.retain());
            return;
        }
        if (state != State.EXPECTING_LOGIN) {
            rejectAndClose(context, AuthenticationOutcome.REJECTED, false, 0);
            return;
        }

        if (frame.content().readableBytes() > V1JsonLoginCodec.MAX_LOGIN_WIRE_BYTES) {
            recordRejected();
            rejectAndClose(context, AuthenticationOutcome.REJECTED, false, 0);
            return;
        }
        final DecodedV1Login decoded;
        try {
            decoded = codec.decode(ByteBufUtil.getBytes(frame.content()));
        } catch (IllegalArgumentException exception) {
            recordRejected();
            rejectAndClose(context, AuthenticationOutcome.REJECTED, false, 0);
            return;
        }

        final AuthenticationAdmissionDecision decision;
        try {
            decision = Objects.requireNonNull(
                    admission.acquire(directPeer(context), decoded.username()),
                    "authentication admission decision");
        } catch (RuntimeException exception) {
            decoded.close();
            recordFailed();
            rejectAndClose(context, AuthenticationOutcome.FAILED, false, 0);
            return;
        }
        if (!decision.allowed()) {
            decoded.close();
            recordAdmissionDenied(decision);
            rejectAndClose(context, AuthenticationOutcome.REJECTED, false, 0);
            return;
        }

        String presentedUsername = decoded.username();
        final AuthenticateCommand command;
        try (decoded) {
            command = decoded.toCommand(CLIENT);
        }
        state = State.AUTHENTICATING;
        try {
            authenticationExecutor.execute(() -> authenticateOffEventLoop(
                    context, presentedUsername, command));
        } catch (RejectedExecutionException exception) {
            command.close();
            recordSaturated();
            rejectAndClose(context, AuthenticationOutcome.REJECTED, false, 0);
        }
    }

    private void authenticateOffEventLoop(
            ChannelHandlerContext context,
            String presentedUsername,
            AuthenticateCommand command) {
        long started = System.nanoTime();
        final LegacyV1LoginResult result;
        try (command) {
            result = login.login(command);
        } catch (RuntimeException exception) {
            schedule(context, () -> {
                recordFailed();
                rejectAndClose(
                        context, AuthenticationOutcome.FAILED, false, elapsed(started));
            });
            return;
        }
        schedule(context, () -> complete(
                context, presentedUsername, result, elapsed(started)));
    }

    private void complete(
            ChannelHandlerContext context,
            String presentedUsername,
            LegacyV1LoginResult result,
            long executionNanos) {
        if (state != State.AUTHENTICATING || !context.channel().isActive()) {
            return;
        }
        if (result instanceof LegacyV1LoginResult.Established established) {
            recordAdmissionSuccess(presentedUsername);
            var identity = established.identity();
            context.channel().attr(V1ConnectionAttributes.AUTHENTICATED).set(identity);
            state = State.AUTHENTICATED;
            recordAccepted(identity.credentialUpgradePending());
            recordCompleted(
                    AuthenticationOutcome.ACCEPTED,
                    identity.credentialUpgradePending(),
                    executionNanos);
            context.writeAndFlush(new TextWebSocketFrame(
                    io.netty.buffer.Unpooled.wrappedBuffer(codec.encodeEstablished(identity))));
            return;
        }
        recordRejected();
        rejectAndClose(context, AuthenticationOutcome.REJECTED, false, executionNanos);
    }

    private void rejectAndClose(
            ChannelHandlerContext context,
            AuthenticationOutcome outcome,
            boolean upgradePending,
            long executionNanos) {
        state = State.TERMINAL;
        recordCompleted(outcome, upgradePending, executionNanos);
        context.writeAndFlush(new TextWebSocketFrame(
                        io.netty.buffer.Unpooled.wrappedBuffer(codec.encodeRejected())))
                .addListener(ChannelFutureListener.CLOSE);
    }

    private static String directPeer(ChannelHandlerContext context) {
        String resolved = context.channel()
                .attr(V2ConnectionAttributes.CLIENT_PEER_ADDRESS)
                .get();
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        if (context.channel().remoteAddress() instanceof java.net.InetSocketAddress remote) {
            return remote.getAddress() == null
                    ? remote.getHostString()
                    : remote.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private void recordAccepted(boolean upgradePending) {
        try {
            events.accepted(upgradePending);
        } catch (RuntimeException ignored) {
            // Diagnostics must not alter an authentication outcome.
        }
    }

    private void recordRejected() {
        try {
            events.rejected();
        } catch (RuntimeException ignored) {
            // Diagnostics must not alter an authentication outcome.
        }
    }

    private void recordFailed() {
        try {
            events.failed();
        } catch (RuntimeException ignored) {
            // Diagnostics must not replace the normalized client failure.
        }
    }

    private void recordSaturated() {
        try {
            events.saturated();
        } catch (RuntimeException ignored) {
            // Diagnostics must not replace the bounded rejection.
        }
    }

    private void recordAdmissionDenied(AuthenticationAdmissionDecision decision) {
        try {
            events.admissionDenied(decision.dimension());
        } catch (RuntimeException ignored) {
            // Diagnostics must not replace the bounded rejection.
        }
    }

    private void recordAdmissionSuccess(String username) {
        try {
            admission.recordSuccess(username);
        } catch (RuntimeException ignored) {
            // A verified login remains valid if limiter cleanup fails.
        }
    }

    private void recordCompleted(
            AuthenticationOutcome outcome,
            boolean upgradePending,
            long executionNanos) {
        try {
            events.completed(outcome, upgradePending, executionNanos);
        } catch (RuntimeException ignored) {
            // Telemetry must not alter an authentication outcome.
        }
    }

    private static void schedule(ChannelHandlerContext context, Runnable completion) {
        try {
            context.executor().execute(completion);
        } catch (RejectedExecutionException exception) {
            context.close();
        }
    }

    private static long elapsed(long started) {
        return Math.max(0, System.nanoTime() - started);
    }
}
