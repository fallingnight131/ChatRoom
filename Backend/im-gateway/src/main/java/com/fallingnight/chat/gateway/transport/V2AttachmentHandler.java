package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.attachment.AttachmentActor;
import com.fallingnight.chat.application.attachment.AttachmentCompletionResult;
import com.fallingnight.chat.application.attachment.AttachmentRegistration;
import com.fallingnight.chat.application.attachment.AttachmentRegistrationPort;
import com.fallingnight.chat.application.attachment.AttachmentRegistrationResult;
import com.fallingnight.chat.application.attachment.AttachmentUploadAuthorizationResult;
import com.fallingnight.chat.application.attachment.AttachmentUploadGrant;
import com.fallingnight.chat.application.attachment.AttachmentUploadService;
import com.fallingnight.chat.application.attachment.RegisteredAttachment;
import com.fallingnight.chat.protocol.v2.AttachmentPayloadPolicy;
import com.fallingnight.chat.protocol.v2.AttachmentReady;
import com.fallingnight.chat.protocol.v2.AttachmentRegistered;
import com.fallingnight.chat.protocol.v2.AttachmentUploadAuthorized;
import com.fallingnight.chat.protocol.v2.AuthorizeAttachmentUpload;
import com.fallingnight.chat.protocol.v2.CompleteAttachmentUpload;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.MessageTypeRegistry;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.RegisterAttachment;
import com.fallingnight.chat.protocol.v2.RequiredUploadHeader;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Inactive serialized transport adapter for V2 attachment metadata commands. */
public final class V2AttachmentHandler extends SimpleChannelInboundHandler<Envelope> {
    static final int MAX_PENDING_COMMANDS = 8;

    private final AttachmentRegistrationPort registrations;
    private final AttachmentUploadService uploads;
    private final Executor executor;
    private final AttachmentEventSink events;
    private final Clock clock;
    private final ArrayDeque<Envelope> pending = new ArrayDeque<>();
    private boolean inFlight;

    public V2AttachmentHandler(
            AttachmentRegistrationPort registrations,
            AttachmentUploadService uploads,
            Executor executor,
            AttachmentEventSink events) {
        this(registrations, uploads, executor, events, Clock.systemUTC());
    }

    V2AttachmentHandler(
            AttachmentRegistrationPort registrations,
            AttachmentUploadService uploads,
            Executor executor,
            AttachmentEventSink events,
            Clock clock) {
        this.registrations = Objects.requireNonNull(registrations, "registrations");
        this.uploads = Objects.requireNonNull(uploads, "uploads");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean acceptInboundMessage(Object message) {
        if (!(message instanceof Envelope envelope)) {
            return false;
        }
        MessageType type = MessageTypeRegistry.find(envelope.getMessageType()).orElse(null);
        return type == MessageType.MESSAGE_TYPE_REGISTER_ATTACHMENT
                || type == MessageType.MESSAGE_TYPE_AUTHORIZE_ATTACHMENT_UPLOAD
                || type == MessageType.MESSAGE_TYPE_COMPLETE_ATTACHMENT_UPLOAD;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, Envelope envelope) {
        if (context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get() == null) {
            writeError(context, envelope, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "authentication is required", false);
            return;
        }
        if (envelope.getKind() != MessageKind.MESSAGE_KIND_COMMAND) {
            events.invalid();
            writeError(context, envelope, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    "attachment requests must be commands", false);
            return;
        }
        if (pending.size() >= MAX_PENDING_COMMANDS) {
            events.saturated();
            writeError(context, envelope, ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "too many pending attachment commands", true);
            return;
        }
        pending.addLast(envelope);
        dispatchNext(context);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        pending.clear();
        context.fireChannelInactive();
    }

    private void dispatchNext(ChannelHandlerContext context) {
        if (inFlight || pending.isEmpty() || !context.channel().isActive()) {
            return;
        }
        Envelope request = pending.removeFirst();
        Work work;
        try {
            work = parse(context, request);
        } catch (InvalidProtocolBufferException | IllegalArgumentException exception) {
            events.invalid();
            writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    "invalid attachment payload", false);
            dispatchNext(context);
            return;
        }
        inFlight = true;
        try {
            executor.execute(() -> execute(context, request, work));
        } catch (RejectedExecutionException exception) {
            inFlight = false;
            events.saturated();
            writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "attachment service is busy", true);
            dispatchNext(context);
        }
    }

    private Work parse(ChannelHandlerContext context, Envelope request)
            throws InvalidProtocolBufferException {
        AuthenticatedConnection identity = Objects.requireNonNull(
                context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get());
        AttachmentActor actor = new AttachmentActor(identity.accountId(), identity.deviceId());
        MessageType type = MessageTypeRegistry.find(request.getMessageType()).orElseThrow();
        if (type == MessageType.MESSAGE_TYPE_REGISTER_ATTACHMENT) {
            RegisterAttachment payload = RegisterAttachment.parseFrom(request.getPayload());
            AttachmentPayloadPolicy.requireValid(payload);
            return new RegisterWork(new AttachmentRegistration(
                    UUID.fromString(payload.getConversationId()), identity.accountId(),
                    identity.deviceId(), payload.getClientAttachmentId(), payload.getFileName(),
                    payload.getMediaType(), payload.getByteSize(),
                    payload.getContentSha256().toByteArray()));
        }
        if (type == MessageType.MESSAGE_TYPE_AUTHORIZE_ATTACHMENT_UPLOAD) {
            AuthorizeAttachmentUpload payload = AuthorizeAttachmentUpload.parseFrom(
                    request.getPayload());
            AttachmentPayloadPolicy.requireValid(payload);
            return new AuthorizeWork(UUID.fromString(payload.getAttachmentId()), actor);
        }
        CompleteAttachmentUpload payload = CompleteAttachmentUpload.parseFrom(
                request.getPayload());
        AttachmentPayloadPolicy.requireValid(payload);
        return new CompleteWork(UUID.fromString(payload.getAttachmentId()), actor);
    }

    private void execute(ChannelHandlerContext context, Envelope request, Work work) {
        Envelope response;
        try {
            if (work instanceof RegisterWork register) {
                response = registrationResponse(
                        request, registrations.register(register.registration()));
            } else if (work instanceof AuthorizeWork authorize) {
                response = authorizationResponse(request, uploads.authorizeUpload(
                        authorize.attachmentId(), authorize.actor()));
            } else {
                CompleteWork complete = (CompleteWork) work;
                response = completionResponse(request, uploads.completeUpload(
                        complete.attachmentId(), complete.actor()));
            }
        } catch (RuntimeException exception) {
            events.failed();
            response = errorEnvelope(request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR,
                    "attachment service is temporarily unavailable", true);
        }
        scheduleCompletion(context, response);
    }

    private Envelope registrationResponse(
            Envelope request, AttachmentRegistrationResult result) {
        if (result == AttachmentRegistrationResult.Rejected.NOT_AUTHORIZED) {
            return denied(request);
        }
        if (result == AttachmentRegistrationResult.Rejected.IDEMPOTENCY_CONFLICT) {
            events.conflict();
            return errorEnvelope(request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_IDEMPOTENCY_CONFLICT,
                    "client attachment id conflicts with an existing attachment", false);
        }
        AttachmentRegistrationResult.Accepted accepted =
                (AttachmentRegistrationResult.Accepted) result;
        RegisteredAttachment attachment = accepted.attachment();
        AttachmentRegistered payload = AttachmentRegistered.newBuilder()
                .setAttachmentId(attachment.attachmentId().toString())
                .setConversationId(attachment.conversationId().toString())
                .setClientAttachmentId(attachment.clientAttachmentId())
                .setDuplicate(accepted.duplicate())
                .build();
        AttachmentPayloadPolicy.requireValid(payload);
        events.registered(accepted.duplicate());
        return response(request, MessageType.MESSAGE_TYPE_ATTACHMENT_REGISTERED,
                payload.toByteString());
    }

    private Envelope authorizationResponse(
            Envelope request, AttachmentUploadAuthorizationResult result) {
        if (result == AttachmentUploadAuthorizationResult.Rejected.NOT_AVAILABLE) {
            return denied(request);
        }
        AttachmentUploadAuthorizationResult.Granted granted =
                (AttachmentUploadAuthorizationResult.Granted) result;
        AttachmentUploadGrant grant = granted.grant();
        AttachmentUploadAuthorized.Builder payload = AttachmentUploadAuthorized.newBuilder()
                .setAttachmentId(granted.attachment().attachmentId().toString())
                .setUploadUri(grant.uploadUri().toString())
                .setExpiresAtEpochMs(grant.expiresAt().toEpochMilli());
        grant.requiredHeaders().entrySet().stream()
                .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                .forEach(header -> payload.addRequiredHeaders(RequiredUploadHeader.newBuilder()
                        .setName(header.getKey()).setValue(header.getValue())));
        AttachmentUploadAuthorized built = payload.build();
        AttachmentPayloadPolicy.requireValid(built);
        events.uploadAuthorized();
        return response(request, MessageType.MESSAGE_TYPE_ATTACHMENT_UPLOAD_AUTHORIZED,
                built.toByteString());
    }

    private Envelope completionResponse(
            Envelope request, AttachmentCompletionResult result) {
        if (result == AttachmentCompletionResult.Rejected.NOT_AVAILABLE) {
            return denied(request);
        }
        if (result == AttachmentCompletionResult.Rejected.OBJECT_MISSING) {
            events.invalid();
            return errorEnvelope(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "attachment upload is not complete", true);
        }
        if (result == AttachmentCompletionResult.Rejected.OBJECT_MISMATCH) {
            events.invalid();
            return errorEnvelope(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "attachment upload could not be verified", false);
        }
        AttachmentCompletionResult.Ready ready = (AttachmentCompletionResult.Ready) result;
        RegisteredAttachment attachment = ready.attachment();
        AttachmentReady payload = AttachmentReady.newBuilder()
                .setAttachmentId(attachment.attachmentId().toString())
                .setConversationId(attachment.conversationId().toString())
                .setDuplicate(ready.duplicate())
                .setReadyAtEpochMs(attachment.readyAt().orElseThrow().toEpochMilli())
                .build();
        AttachmentPayloadPolicy.requireValid(payload);
        events.ready(ready.duplicate());
        return response(request, MessageType.MESSAGE_TYPE_ATTACHMENT_READY,
                payload.toByteString());
    }

    private Envelope denied(Envelope request) {
        events.denied();
        return errorEnvelope(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                "attachment is not available", false);
    }

    private void scheduleCompletion(ChannelHandlerContext context, Envelope response) {
        if (context.executor().isShuttingDown()) {
            return;
        }
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

    private void writeError(
            ChannelHandlerContext context, Envelope request, ProtocolErrorCode code,
            String safeMessage, boolean retryable) {
        context.writeAndFlush(errorEnvelope(request, code, safeMessage, retryable));
    }

    private Envelope errorEnvelope(
            Envelope request, ProtocolErrorCode code, String safeMessage, boolean retryable) {
        ProtocolError error = ProtocolError.newBuilder().setCode(code)
                .setSafeMessage(safeMessage).setRetryable(retryable).build();
        return Envelope.newBuilder().setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_ERROR)
                .setMessageType(MessageType.MESSAGE_TYPE_PROTOCOL_ERROR_VALUE)
                .setRequestId(request.getRequestId()).setSessionId(request.getSessionId())
                .setClientMessageId(request.getClientMessageId()).setSentAtEpochMs(clock.millis())
                .setPayload(error.toByteString()).build();
    }

    private Envelope response(Envelope request, MessageType type, ByteString payload) {
        return Envelope.newBuilder().setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_RESPONSE).setMessageType(type.getNumber())
                .setRequestId(request.getRequestId()).setSessionId(request.getSessionId())
                .setClientMessageId(request.getClientMessageId()).setSentAtEpochMs(clock.millis())
                .setPayload(payload).build();
    }

    private sealed interface Work { }
    private record RegisterWork(AttachmentRegistration registration) implements Work { }
    private record AuthorizeWork(UUID attachmentId, AttachmentActor actor) implements Work { }
    private record CompleteWork(UUID attachmentId, AttachmentActor actor) implements Work { }
}
