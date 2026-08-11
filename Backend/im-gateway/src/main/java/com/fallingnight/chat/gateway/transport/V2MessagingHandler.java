package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.messaging.MessageHistoryPort;
import com.fallingnight.chat.application.messaging.MessageHistoryQuery;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.MessageSubmission;
import com.fallingnight.chat.application.messaging.MessageSubmissionPort;
import com.fallingnight.chat.application.messaging.MessageSubmissionResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageAccepted;
import com.fallingnight.chat.protocol.v2.MessageHistoryPage;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageRecord;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.MessageTypeRegistry;
import com.fallingnight.chat.protocol.v2.MessagingPayloadPolicy;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.ReadMessageHistory;
import com.fallingnight.chat.protocol.v2.SubmitMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Serializes authenticated message database work per connection off the event loop. */
public final class V2MessagingHandler extends SimpleChannelInboundHandler<Envelope> {
    static final int MAX_PENDING_COMMANDS = 16;

    private final MessageSubmissionPort submissions;
    private final MessageHistoryPort history;
    private final Executor executor;
    private final Clock clock;
    private final ArrayDeque<Envelope> pending = new ArrayDeque<>();
    private boolean inFlight;

    public V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            Executor executor) {
        this(submissions, history, executor, Clock.systemUTC());
    }

    V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            Executor executor,
            Clock clock) {
        this.submissions = Objects.requireNonNull(submissions, "submissions");
        this.history = Objects.requireNonNull(history, "history");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, Envelope envelope) {
        if (context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get() == null) {
            writeError(
                    context,
                    envelope,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "authentication is required",
                    false);
            return;
        }
        MessageType type = MessageTypeRegistry.find(envelope.getMessageType()).orElse(null);
        if (type != MessageType.MESSAGE_TYPE_SUBMIT_MESSAGE
                && type != MessageType.MESSAGE_TYPE_READ_MESSAGE_HISTORY) {
            writeError(
                    context,
                    envelope,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE,
                    "unsupported authenticated message type",
                    false);
            return;
        }
        if (envelope.getKind() != MessageKind.MESSAGE_KIND_COMMAND) {
            writeError(
                    context,
                    envelope,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    "messaging requests must be commands",
                    false);
            return;
        }
        if (pending.size() >= MAX_PENDING_COMMANDS) {
            writeError(
                    context,
                    envelope,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "too many pending messaging commands",
                    true);
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
        Envelope envelope = pending.removeFirst();
        final Work work;
        try {
            work = parseWork(context, envelope);
        } catch (InvalidProtocolBufferException | IllegalArgumentException exception) {
            writeError(
                    context,
                    envelope,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    "invalid messaging payload",
                    false);
            dispatchNext(context);
            return;
        }
        inFlight = true;
        try {
            executor.execute(() -> executeOffEventLoop(context, envelope, work));
        } catch (RejectedExecutionException exception) {
            inFlight = false;
            writeError(
                    context,
                    envelope,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "messaging is busy",
                    true);
            dispatchNext(context);
        }
    }

    private Work parseWork(ChannelHandlerContext context, Envelope envelope)
            throws InvalidProtocolBufferException {
        AuthenticatedConnection identity = Objects.requireNonNull(
                context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get(),
                "authenticated identity");
        MessageType type = MessageTypeRegistry.find(envelope.getMessageType()).orElseThrow();
        if (type == MessageType.MESSAGE_TYPE_SUBMIT_MESSAGE) {
            SubmitMessage payload = SubmitMessage.parseFrom(envelope.getPayload());
            MessagingPayloadPolicy.requireValid(payload, envelope.getClientMessageId());
            return new SubmitWork(new MessageSubmission(
                    UUID.fromString(payload.getConversationId()),
                    identity.accountId(),
                    identity.deviceId(),
                    envelope.getClientMessageId(),
                    payload.getContentType(),
                    payload.getContent().toByteArray()));
        }
        ReadMessageHistory payload = ReadMessageHistory.parseFrom(envelope.getPayload());
        MessagingPayloadPolicy.requireValid(payload);
        return new HistoryWork(new MessageHistoryQuery(
                UUID.fromString(payload.getConversationId()),
                identity.accountId(),
                payload.getAfterSequence(),
                payload.getLimit()));
    }

    private void executeOffEventLoop(
            ChannelHandlerContext context, Envelope request, Work work) {
        final Envelope response;
        try {
            response = work instanceof SubmitWork submit
                    ? submitResponse(
                            request,
                            submit.submission(),
                            submissions.submit(submit.submission()))
                    : historyResponse(
                            request,
                            ((HistoryWork) work).query(),
                            history.readAfter(((HistoryWork) work).query()));
        } catch (RuntimeException exception) {
            scheduleCompletion(context, errorEnvelope(
                    request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR,
                    "messaging is temporarily unavailable",
                    true));
            return;
        }
        scheduleCompletion(context, response);
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

    private Envelope submitResponse(
            Envelope request,
            MessageSubmission submission,
            MessageSubmissionResult result) {
        if (result == MessageSubmissionResult.Rejected.NOT_AUTHORIZED) {
            return errorEnvelope(
                    request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                    "not authorized",
                    false);
        }
        if (result == MessageSubmissionResult.Rejected.IDEMPOTENCY_CONFLICT) {
            return errorEnvelope(
                    request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_IDEMPOTENCY_CONFLICT,
                    "client message id conflicts with an accepted message",
                    false);
        }
        MessageSubmissionResult.Accepted accepted = (MessageSubmissionResult.Accepted) result;
        MessageAccepted payload = MessageAccepted.newBuilder()
                .setConversationId(submission.conversationId().toString())
                .setMessageId(accepted.messageId().toString())
                .setConversationSequence(accepted.conversationSequence())
                .setAcceptedAtEpochMs(accepted.acceptedAt().toEpochMilli())
                .setDuplicate(accepted.duplicate())
                .build();
        MessagingPayloadPolicy.requireValid(payload);
        return responseEnvelope(
                request, MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED, payload.toByteString());
    }

    private Envelope historyResponse(
            Envelope request,
            MessageHistoryQuery query,
            MessageHistoryResult result) {
        if (result == MessageHistoryResult.Rejected.NOT_AUTHORIZED) {
            return errorEnvelope(
                    request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                    "not authorized",
                    false);
        }
        MessageHistoryResult.Page page = (MessageHistoryResult.Page) result;
        MessageHistoryPage.Builder payload = MessageHistoryPage.newBuilder()
                .setConversationId(query.conversationId().toString())
                .setNextSequence(page.nextSequence())
                .setLatestSequence(page.latestSequence())
                .setHasMore(page.hasMore());
        for (StoredMessage message : page.messages()) {
            payload.addMessages(MessageRecord.newBuilder()
                    .setConversationId(message.conversationId().toString())
                    .setMessageId(message.messageId().toString())
                    .setConversationSequence(message.conversationSequence())
                    .setSenderAccountId(message.senderAccountId().toString())
                    .setSenderDeviceId(message.senderDeviceId().toString())
                    .setClientMessageId(message.clientMessageId())
                    .setContentType(message.messageType())
                    .setContent(ByteString.copyFrom(message.payload()))
                    .setAcceptedAtEpochMs(message.acceptedAt().toEpochMilli()));
        }
        MessageHistoryPage built = payload.build();
        MessagingPayloadPolicy.requireValid(built);
        return responseEnvelope(
                request, MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE, built.toByteString());
    }

    private void writeError(
            ChannelHandlerContext context,
            Envelope request,
            ProtocolErrorCode code,
            String safeMessage,
            boolean retryable) {
        context.writeAndFlush(errorEnvelope(request, code, safeMessage, retryable));
    }

    private Envelope errorEnvelope(
            Envelope request,
            ProtocolErrorCode code,
            String safeMessage,
            boolean retryable) {
        ProtocolError error = ProtocolError.newBuilder()
                .setCode(code)
                .setSafeMessage(safeMessage)
                .setRetryable(retryable)
                .build();
        return Envelope.newBuilder()
                .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_ERROR)
                .setMessageType(MessageType.MESSAGE_TYPE_PROTOCOL_ERROR_VALUE)
                .setRequestId(request.getRequestId())
                .setSessionId(request.getSessionId())
                .setClientMessageId(request.getClientMessageId())
                .setSentAtEpochMs(clock.millis())
                .setPayload(error.toByteString())
                .build();
    }

    private Envelope responseEnvelope(
            Envelope request, MessageType type, ByteString payload) {
        return Envelope.newBuilder()
                .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_RESPONSE)
                .setMessageType(type.getNumber())
                .setRequestId(request.getRequestId())
                .setSessionId(request.getSessionId())
                .setClientMessageId(request.getClientMessageId())
                .setSentAtEpochMs(clock.millis())
                .setPayload(payload)
                .build();
    }

    private sealed interface Work {}

    private record SubmitWork(MessageSubmission submission) implements Work {}

    private record HistoryWork(MessageHistoryQuery query) implements Work {}
}
