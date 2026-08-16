package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.identity.AuthenticatedDeviceActor;
import com.fallingnight.chat.application.notification.IssuedWebPushHttpCredential;
import com.fallingnight.chat.application.notification.WebPushHttpCredentialIssueResult;
import com.fallingnight.chat.application.notification.WebPushHttpCredentialIssueService;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.WebPushHttpCredentialIssued;
import com.google.protobuf.ByteString;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Web-only, capability-gated, serialized issuance outside the Netty event loop. */
public final class V2WebPushHttpCredentialHandler extends SimpleChannelInboundHandler<Envelope> {
    static final int MAX_PENDING_COMMANDS = 4;

    private final WebPushHttpCredentialIssueService credentials;
    private final Executor executor;
    private final WebPushHttpCredentialEventSink events;
    private final Clock clock;
    private final ArrayDeque<PendingIssue> pending = new ArrayDeque<>();
    private boolean inFlight;

    public V2WebPushHttpCredentialHandler(WebPushHttpCredentialIssueService credentials,
            Executor executor, WebPushHttpCredentialEventSink events) {
        this(credentials, executor, events, Clock.systemUTC());
    }

    V2WebPushHttpCredentialHandler(WebPushHttpCredentialIssueService credentials,
            Executor executor, WebPushHttpCredentialEventSink events, Clock clock) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean acceptInboundMessage(Object message) {
        return message instanceof Envelope envelope
                && envelope.getMessageType()
                        == MessageType.MESSAGE_TYPE_ISSUE_WEB_PUSH_HTTP_CREDENTIAL_VALUE;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, Envelope request) {
        AuthenticatedConnection identity =
                context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) {
            writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "authentication is required", false, null);
            return;
        }
        Set<ClientCapability> capabilities =
                context.channel().attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
        if (capabilities == null || !capabilities.contains(
                ClientCapability.CLIENT_CAPABILITY_WEB_PUSH_HTTP_CREDENTIAL)) {
            writeError(context, request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE,
                    "Web Push credential issuance was not negotiated", false,
                    identity.sessionId());
            return;
        }
        if (request.getKind() != MessageKind.MESSAGE_KIND_COMMAND
                || !request.getPayload().isEmpty()
                || !request.getClientMessageId().isEmpty()) {
            writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    "invalid Web Push credential request", false, identity.sessionId());
            return;
        }
        if (pending.size() >= MAX_PENDING_COMMANDS) {
            safe(WebPushHttpCredentialEventSink::saturated);
            writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "too many pending credential requests", true, identity.sessionId());
            return;
        }
        pending.addLast(new PendingIssue(request, new AuthenticatedDeviceActor(
                identity.accountId(), identity.deviceId(), identity.sessionId())));
        dispatchNext(context);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        pending.clear();
        context.fireChannelInactive();
    }

    private void dispatchNext(ChannelHandlerContext context) {
        if (inFlight || pending.isEmpty() || !context.channel().isActive()) return;
        PendingIssue issue = pending.removeFirst();
        inFlight = true;
        try {
            executor.execute(() -> execute(context, issue));
        } catch (RejectedExecutionException exception) {
            inFlight = false;
            safe(WebPushHttpCredentialEventSink::saturated);
            writeError(context, issue.request(),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "credential service is busy", true, issue.actor().sessionId());
            dispatchNext(context);
        }
    }

    private void execute(ChannelHandlerContext context, PendingIssue issue) {
        Envelope response;
        try {
            WebPushHttpCredentialIssueResult result = credentials.issue(issue.actor());
            if (result instanceof WebPushHttpCredentialIssueResult.Issued issued) {
                try (IssuedWebPushHttpCredential credential = issued.credential()) {
                    WebPushHttpCredentialIssued payload = credential.withTokenCopies(
                            (bearer, csrf) -> WebPushHttpCredentialIssued.newBuilder()
                                    .setBearerTokenAscii(ByteString.copyFrom(bearer))
                                    .setCsrfTokenAscii(ByteString.copyFrom(csrf))
                                    .setExpiresAtEpochMs(credential.expiresAt().toEpochMilli())
                                    .build());
                    safe(WebPushHttpCredentialEventSink::issued);
                    response = envelope(issue.request(), MessageKind.MESSAGE_KIND_RESPONSE,
                            MessageType.MESSAGE_TYPE_WEB_PUSH_HTTP_CREDENTIAL_ISSUED,
                            payload.toByteString(), issue.actor().sessionId());
                }
            } else {
                safe(WebPushHttpCredentialEventSink::denied);
                response = error(issue.request(),
                        ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                        "credential is unavailable", false, issue.actor().sessionId());
            }
        } catch (RuntimeException exception) {
            safe(WebPushHttpCredentialEventSink::failed);
            response = error(issue.request(), ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR,
                    "credential service is temporarily unavailable", true,
                    issue.actor().sessionId());
        }
        scheduleCompletion(context, response);
    }

    private void scheduleCompletion(ChannelHandlerContext context, Envelope response) {
        if (context.executor().isShuttingDown()) return;
        try {
            context.executor().execute(() -> {
                inFlight = false;
                if (context.channel().isActive()) {
                    context.writeAndFlush(response);
                    dispatchNext(context);
                } else {
                    pending.clear();
                }
            });
        } catch (RejectedExecutionException exception) {
            pending.clear();
        }
    }

    private void writeError(ChannelHandlerContext context, Envelope request,
            ProtocolErrorCode code, String message, boolean retryable, UUID sessionId) {
        context.writeAndFlush(error(request, code, message, retryable, sessionId));
    }

    private Envelope error(Envelope request, ProtocolErrorCode code,
            String message, boolean retryable, UUID sessionId) {
        ProtocolError payload = ProtocolError.newBuilder().setCode(code)
                .setSafeMessage(message).setRetryable(retryable).build();
        return envelope(request, MessageKind.MESSAGE_KIND_ERROR,
                MessageType.MESSAGE_TYPE_PROTOCOL_ERROR, payload.toByteString(), sessionId);
    }

    private Envelope envelope(Envelope request, MessageKind kind, MessageType type,
            ByteString payload, UUID sessionId) {
        var builder = Envelope.newBuilder()
                .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(kind)
                .setMessageType(type.getNumber())
                .setRequestId(request.getRequestId())
                .setSentAtEpochMs(clock.millis())
                .setPayload(payload);
        if (sessionId != null) builder.setSessionId(sessionId.toString());
        return builder.build();
    }

    private void safe(java.util.function.Consumer<WebPushHttpCredentialEventSink> event) {
        try {
            event.accept(events);
        } catch (RuntimeException ignored) { }
    }

    private record PendingIssue(Envelope request, AuthenticatedDeviceActor actor) { }
}
