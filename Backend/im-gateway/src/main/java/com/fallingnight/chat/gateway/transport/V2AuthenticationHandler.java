package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.identity.AuthenticateCommand;
import com.fallingnight.chat.application.identity.AuthenticationResult;
import com.fallingnight.chat.application.identity.AuthenticationUseCase;
import com.fallingnight.chat.application.identity.ClientDescriptor;
import com.fallingnight.chat.application.identity.IssuedSession;
import com.fallingnight.chat.application.identity.ResumeSessionCommand;
import com.fallingnight.chat.application.identity.SessionResumeUseCase;
import com.fallingnight.chat.protocol.v2.Authenticate;
import com.fallingnight.chat.protocol.v2.AuthenticationPayloadPolicy;
import com.fallingnight.chat.protocol.v2.AuthenticationRejected;
import com.fallingnight.chat.protocol.v2.AuthenticationRejectionReason;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.MessageTypeRegistry;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.ResumeSession;
import com.fallingnight.chat.protocol.v2.SessionEstablished;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Moves a negotiated V2 connection through one fresh-login attempt and binds
 * the resulting server identity to the channel. Password work never runs on
 * the Netty event loop.
 */
public final class V2AuthenticationHandler extends SimpleChannelInboundHandler<Envelope> {
    public static final long SATURATION_RETRY_AFTER_MS = 1_000;
    public static final int MAX_AUTHENTICATE_BYTES =
            AuthenticationPayloadPolicy.MAX_USERNAME_BYTES
                    + AuthenticationPayloadPolicy.MAX_PASSWORD_BYTES
                    + 32;

    private enum State {
        EXPECTING_AUTHENTICATION,
        AUTHENTICATING,
        AUTHENTICATED,
        TERMINAL
    }

    private final AuthenticationUseCase authentication;
    private final SessionResumeUseCase sessionResume;
    private final Executor authenticationExecutor;
    private final AuthenticationEventSink events;
    private final AuthenticationAdmissionControl admission;
    private final Clock clock;
    private State state = State.EXPECTING_AUTHENTICATION;

    public V2AuthenticationHandler(
            AuthenticationUseCase authentication,
            Executor authenticationExecutor) {
        this(
                authentication,
                rejectingResumeUseCase(),
                authenticationExecutor,
                AuthenticationAdmissionControl.allowAll(),
                AuthenticationEventSink.noop(),
                Clock.systemUTC());
    }

    public V2AuthenticationHandler(
            AuthenticationUseCase authentication,
            Executor authenticationExecutor,
            AuthenticationAdmissionControl admission) {
        this(
                authentication,
                rejectingResumeUseCase(),
                authenticationExecutor,
                admission,
                AuthenticationEventSink.noop(),
                Clock.systemUTC());
    }

    public V2AuthenticationHandler(
            AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume,
            Executor authenticationExecutor,
            AuthenticationAdmissionControl admission) {
        this(
                authentication,
                sessionResume,
                authenticationExecutor,
                admission,
                AuthenticationEventSink.noop(),
                Clock.systemUTC());
    }

    V2AuthenticationHandler(
            AuthenticationUseCase authentication,
            Executor authenticationExecutor,
            AuthenticationEventSink events,
            Clock clock) {
        this(
                authentication,
                rejectingResumeUseCase(),
                authenticationExecutor,
                AuthenticationAdmissionControl.allowAll(),
                events,
                clock);
    }

    V2AuthenticationHandler(
            AuthenticationUseCase authentication,
            Executor authenticationExecutor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events,
            Clock clock) {
        this(
                authentication,
                rejectingResumeUseCase(),
                authenticationExecutor,
                admission,
                events,
                clock);
    }

    V2AuthenticationHandler(
            AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume,
            Executor authenticationExecutor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events,
            Clock clock) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.sessionResume = Objects.requireNonNull(sessionResume, "sessionResume");
        this.authenticationExecutor = Objects.requireNonNull(
                authenticationExecutor, "authenticationExecutor");
        this.events = Objects.requireNonNull(events, "events");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, Envelope envelope) {
        if (state == State.AUTHENTICATED) {
            forwardAuthenticated(context, envelope);
            return;
        }
        if (state != State.EXPECTING_AUTHENTICATION) {
            failProtocol(
                    context,
                    envelope.getRequestId(),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "authentication is already in progress");
            return;
        }

        ClientDescriptor client = context.channel()
                .attr(V2ConnectionAttributes.NEGOTIATED_CLIENT)
                .get();
        if (client == null) {
            failProtocol(
                    context,
                    envelope.getRequestId(),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "protocol negotiation is required");
            return;
        }

        MessageType type = MessageTypeRegistry.find(envelope.getMessageType()).orElse(null);
        if (type == MessageType.MESSAGE_TYPE_RESUME_SESSION) {
            handleResume(context, envelope, client);
            return;
        }
        if (type != MessageType.MESSAGE_TYPE_AUTHENTICATE
                || envelope.getKind() != MessageKind.MESSAGE_KIND_COMMAND) {
            failProtocol(
                    context,
                    envelope.getRequestId(),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "authentication is required");
            return;
        }
        if (envelope.getPayload().size() > MAX_AUTHENTICATE_BYTES) {
            failInvalidAuthentication(context, envelope.getRequestId());
            return;
        }

        final Authenticate payload;
        try {
            payload = Authenticate.parseFrom(envelope.getPayload());
            AuthenticationPayloadPolicy.requireValid(payload);
        } catch (InvalidProtocolBufferException | IllegalArgumentException exception) {
            failInvalidAuthentication(context, envelope.getRequestId());
            return;
        }

        final AuthenticationAdmissionDecision admissionDecision;
        try {
            admissionDecision = Objects.requireNonNull(
                    admission.acquire(directPeer(context), payload.getUsername()),
                    "authentication admission decision");
        } catch (RuntimeException exception) {
            recordFailed();
            failInternal(context, envelope.getRequestId());
            return;
        }
        if (!admissionDecision.allowed()) {
            recordAdmissionDenied(admissionDecision.dimension());
            rejectAuthentication(
                    context,
                    envelope.getRequestId(),
                    AuthenticationRejectionReason.AUTHENTICATION_REJECTION_REASON_RATE_LIMITED,
                    admissionDecision.retryAfterMs());
            return;
        }

        byte[] password = payload.getPasswordUtf8().toByteArray();
        final AuthenticateCommand command;
        try {
            command = new AuthenticateCommand(payload.getUsername(), password, client);
        } finally {
            Arrays.fill(password, (byte) 0);
        }

        state = State.AUTHENTICATING;
        try {
            authenticationExecutor.execute(() -> authenticateOffEventLoop(
                    context, envelope.getRequestId(), payload.getUsername(), command));
        } catch (RejectedExecutionException exception) {
            command.close();
            recordSaturated();
            rejectAuthentication(
                    context,
                    envelope.getRequestId(),
                    AuthenticationRejectionReason.AUTHENTICATION_REJECTION_REASON_RATE_LIMITED,
                    SATURATION_RETRY_AFTER_MS);
        }
    }

    private void handleResume(
            ChannelHandlerContext context, Envelope envelope, ClientDescriptor client) {
        if (envelope.getKind() != MessageKind.MESSAGE_KIND_COMMAND
                || envelope.getPayload().size() > 256) {
            failInvalidAuthentication(context, envelope.getRequestId());
            return;
        }
        final ResumeSession payload;
        try {
            payload = ResumeSession.parseFrom(envelope.getPayload());
        } catch (InvalidProtocolBufferException exception) {
            failInvalidAuthentication(context, envelope.getRequestId());
            return;
        }
        try {
            AuthenticationPayloadPolicy.requireValid(payload);
        } catch (IllegalArgumentException exception) {
            rejectGenericAuthentication(context, envelope.getRequestId());
            return;
        }

        final AuthenticationAdmissionDecision decision;
        try {
            decision = Objects.requireNonNull(
                    admission.acquireResume(directPeer(context)),
                    "session resume admission decision");
        } catch (RuntimeException exception) {
            recordFailed();
            failInternal(context, envelope.getRequestId());
            return;
        }
        if (!decision.allowed()) {
            recordAdmissionDenied(decision.dimension());
            rejectAuthentication(
                    context,
                    envelope.getRequestId(),
                    AuthenticationRejectionReason.AUTHENTICATION_REJECTION_REASON_RATE_LIMITED,
                    decision.retryAfterMs());
            return;
        }

        final UUID sessionId;
        try {
            sessionId = UUID.fromString(payload.getSessionId());
        } catch (IllegalArgumentException exception) {
            rejectGenericAuthentication(context, envelope.getRequestId());
            return;
        }

        byte[] token = payload.getResumeToken().toByteArray();
        final ResumeSessionCommand command;
        try {
            command = new ResumeSessionCommand(sessionId, token, client);
        } finally {
            Arrays.fill(token, (byte) 0);
        }
        state = State.AUTHENTICATING;
        try {
            authenticationExecutor.execute(() -> resumeOffEventLoop(
                    context, envelope.getRequestId(), command));
        } catch (RejectedExecutionException exception) {
            command.close();
            recordSaturated();
            rejectAuthentication(
                    context,
                    envelope.getRequestId(),
                    AuthenticationRejectionReason.AUTHENTICATION_REJECTION_REASON_RATE_LIMITED,
                    SATURATION_RETRY_AFTER_MS);
        }
    }

    private void resumeOffEventLoop(
            ChannelHandlerContext context, String requestId, ResumeSessionCommand command) {
        long startedNanos = System.nanoTime();
        final AuthenticationResult result;
        try (command) {
            result = sessionResume.resume(command);
        } catch (RuntimeException exception) {
            long executionNanos = elapsedNanos(startedNanos);
            schedule(context, () -> {
                recordFailed();
                recordCompleted(AuthenticationOutcome.FAILED, false, executionNanos);
                failInternal(context, requestId);
            }, null);
            return;
        }
        long executionNanos = elapsedNanos(startedNanos);
        schedule(
                context,
                () -> completeAuthentication(context, requestId, null, result, executionNanos),
                result);
    }

    private void authenticateOffEventLoop(
            ChannelHandlerContext context,
            String requestId,
            String presentedUsername,
            AuthenticateCommand command) {
        long startedNanos = System.nanoTime();
        final AuthenticationResult result;
        try (command) {
            result = authentication.authenticate(command);
        } catch (RuntimeException exception) {
            long executionNanos = elapsedNanos(startedNanos);
            schedule(context, () -> {
                recordFailed();
                recordCompleted(AuthenticationOutcome.FAILED, false, executionNanos);
                failInternal(context, requestId);
            }, null);
            return;
        }
        long executionNanos = elapsedNanos(startedNanos);
        schedule(
                context,
                () -> completeAuthentication(
                        context, requestId, presentedUsername, result, executionNanos),
                result);
    }

    private void completeAuthentication(
            ChannelHandlerContext context,
            String requestId,
            String presentedUsername,
            AuthenticationResult result,
            long executionNanos) {
        if (state != State.AUTHENTICATING || !context.channel().isActive()) {
            closeResult(result);
            return;
        }
        if (result == null) {
            recordFailed();
            recordCompleted(AuthenticationOutcome.FAILED, false, executionNanos);
            failInternal(context, requestId);
            return;
        }
        if (result == AuthenticationResult.Rejected.INSTANCE) {
            recordRejected();
            recordCompleted(AuthenticationOutcome.REJECTED, false, executionNanos);
            rejectAuthentication(
                    context,
                    requestId,
                    AuthenticationRejectionReason.AUTHENTICATION_REJECTION_REASON_REJECTED,
                    0);
            return;
        }

        AuthenticationResult.Established established = (AuthenticationResult.Established) result;
        if (presentedUsername != null) {
            recordAdmissionSuccess(presentedUsername);
        }
        try (IssuedSession session = established.session()) {
            ByteString resumeToken = session.resumeToken().withCopy(ByteString::copyFrom);
            context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).set(
                    new AuthenticatedConnection(
                            session.accountId(), session.deviceId(), session.sessionId()));
            state = State.AUTHENTICATED;
            context.fireUserEventTriggered(V2ConnectionPhaseEvent.AUTHENTICATED);
            recordAccepted(established.credentialUpgradePending());
            recordCompleted(
                    AuthenticationOutcome.ACCEPTED,
                    established.credentialUpgradePending(),
                    executionNanos);

            SessionEstablished payload = SessionEstablished.newBuilder()
                    .setAccountId(session.accountId().toString())
                    .setDeviceId(session.deviceId().toString())
                    .setSessionId(session.sessionId().toString())
                    .setResumeToken(resumeToken)
                    .setExpiresAtEpochMs(session.expiresAt().toEpochMilli())
                    .setDisplayName(session.displayName())
                    .build();
            context.writeAndFlush(responseEnvelope(
                    MessageKind.MESSAGE_KIND_RESPONSE,
                    MessageType.MESSAGE_TYPE_SESSION_ESTABLISHED,
                    requestId,
                    session.sessionId().toString(),
                    payload.toByteString()));
        }
    }

    private void forwardAuthenticated(ChannelHandlerContext context, Envelope envelope) {
        AuthenticatedConnection connection = context.channel()
                .attr(V2ConnectionAttributes.AUTHENTICATED)
                .get();
        if (connection == null
                || !connection.sessionId().toString().equals(envelope.getSessionId())) {
            failProtocol(
                    context,
                    envelope.getRequestId(),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "session does not match the authenticated connection");
            return;
        }
        context.fireChannelRead(envelope);
    }

    private void failInvalidAuthentication(ChannelHandlerContext context, String requestId) {
        failProtocol(
                context,
                requestId,
                ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                "invalid authentication payload");
    }

    private void rejectGenericAuthentication(ChannelHandlerContext context, String requestId) {
        rejectAuthentication(
                context,
                requestId,
                AuthenticationRejectionReason.AUTHENTICATION_REJECTION_REASON_REJECTED,
                0);
    }

    private void rejectAuthentication(
            ChannelHandlerContext context,
            String requestId,
            AuthenticationRejectionReason reason,
            long retryAfterMs) {
        state = State.TERMINAL;
        AuthenticationRejected payload = AuthenticationRejected.newBuilder()
                .setReason(reason)
                .setRetryAfterMs(retryAfterMs)
                .build();
        context.writeAndFlush(responseEnvelope(
                        MessageKind.MESSAGE_KIND_ERROR,
                        MessageType.MESSAGE_TYPE_AUTHENTICATION_REJECTED,
                        requestId,
                        "",
                        payload.toByteString()))
                .addListener(ChannelFutureListener.CLOSE);
    }

    private void failInternal(ChannelHandlerContext context, String requestId) {
        failProtocol(
                context,
                requestId,
                ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR,
                "authentication is temporarily unavailable");
    }

    private void failProtocol(
            ChannelHandlerContext context,
            String requestId,
            ProtocolErrorCode code,
            String safeMessage) {
        state = State.TERMINAL;
        ProtocolError error = ProtocolError.newBuilder()
                .setCode(code)
                .setSafeMessage(safeMessage)
                .setRetryable(code == ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR)
                .build();
        context.writeAndFlush(responseEnvelope(
                        MessageKind.MESSAGE_KIND_ERROR,
                        MessageType.MESSAGE_TYPE_PROTOCOL_ERROR,
                        requestId,
                        "",
                        error.toByteString()))
                .addListener(ChannelFutureListener.CLOSE);
    }

    private Envelope responseEnvelope(
            MessageKind kind,
            MessageType type,
            String requestId,
            String sessionId,
            ByteString payload) {
        return Envelope.newBuilder()
                .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(kind)
                .setMessageType(type.getNumber())
                .setRequestId(requestId)
                .setSessionId(sessionId)
                .setSentAtEpochMs(clock.millis())
                .setPayload(payload)
                .build();
    }

    private void recordAccepted(boolean credentialUpgradePending) {
        try {
            events.accepted(credentialUpgradePending);
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
            // Diagnostics must not replace the bounded admission response.
        }
    }

    private void recordAdmissionDenied(AuthenticationLimitDimension dimension) {
        try {
            events.admissionDenied(dimension);
        } catch (RuntimeException ignored) {
            // Diagnostics must not replace the bounded admission response.
        }
    }

    private void recordAdmissionSuccess(String presentedUsername) {
        try {
            admission.recordSuccess(presentedUsername);
        } catch (RuntimeException ignored) {
            // A verified login remains valid if ephemeral limiter cleanup fails.
        }
    }

    private void recordCompleted(
            AuthenticationOutcome outcome,
            boolean credentialUpgradePending,
            long executionNanos) {
        try {
            events.completed(outcome, credentialUpgradePending, executionNanos);
        } catch (RuntimeException ignored) {
            // Telemetry must not alter an authentication outcome.
        }
    }

    private static long elapsedNanos(long startedNanos) {
        return Math.max(0, System.nanoTime() - startedNanos);
    }

    private static SessionResumeUseCase rejectingResumeUseCase() {
        return command -> {
            command.close();
            return AuthenticationResult.Rejected.INSTANCE;
        };
    }

    private static String directPeer(ChannelHandlerContext context) {
        String resolved = context.channel()
                .attr(V2ConnectionAttributes.CLIENT_PEER_ADDRESS)
                .get();
        if (resolved != null) {
            return resolved;
        }
        SocketAddress remote = context.channel().remoteAddress();
        if (remote instanceof InetSocketAddress address && address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return "<unknown>";
    }

    private static void schedule(
            ChannelHandlerContext context,
            Runnable completion,
            AuthenticationResult resultToCloseOnRejection) {
        try {
            context.executor().execute(completion);
        } catch (RejectedExecutionException exception) {
            closeResult(resultToCloseOnRejection);
            context.close();
        }
    }

    private static void closeResult(AuthenticationResult result) {
        if (result instanceof AuthenticationResult.Established established) {
            established.session().close();
        }
    }
}
