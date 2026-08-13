package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.conversation.ConversationDirectoryCursor;
import com.fallingnight.chat.application.conversation.ConversationDirectoryPage;
import com.fallingnight.chat.application.conversation.ConversationDirectoryPort;
import com.fallingnight.chat.application.conversation.ConversationDirectoryQuery;
import com.fallingnight.chat.application.conversation.ConversationSummary;
import com.fallingnight.chat.application.messaging.MessageHistoryPort;
import com.fallingnight.chat.application.messaging.MessageHistoryQuery;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.MessageMention;
import com.fallingnight.chat.application.messaging.ConversationHistoryEntry;
import com.fallingnight.chat.application.messaging.MessageSubmission;
import com.fallingnight.chat.application.messaging.MessageSubmissionPort;
import com.fallingnight.chat.application.messaging.MessageSubmissionResult;
import com.fallingnight.chat.application.messaging.MessageReactionCommand;
import com.fallingnight.chat.application.messaging.MessageReactionKind;
import com.fallingnight.chat.application.messaging.MessageReactionPort;
import com.fallingnight.chat.application.messaging.MessageReactionResult;
import com.fallingnight.chat.application.messaging.MessagePinCommand;
import com.fallingnight.chat.application.messaging.MessagePinPort;
import com.fallingnight.chat.application.messaging.MessagePinResult;
import com.fallingnight.chat.application.messaging.MessageEditCommand;
import com.fallingnight.chat.application.messaging.MessageEditPort;
import com.fallingnight.chat.application.messaging.MessageEditResult;
import com.fallingnight.chat.application.messaging.MessageForwardCommand;
import com.fallingnight.chat.application.messaging.MessageForwardPort;
import com.fallingnight.chat.application.messaging.MessageForwardResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.ConversationDirectoryRecord;
import com.fallingnight.chat.protocol.v2.ConversationPayloadPolicy;
import com.fallingnight.chat.protocol.v2.ListConversations;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageAccepted;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.MessageHistoryPage;
import com.fallingnight.chat.protocol.v2.ConversationEntryRecord;
import com.fallingnight.chat.protocol.v2.MessageRecalledRecord;
import com.fallingnight.chat.protocol.v2.MessagesDeletedRecord;
import com.fallingnight.chat.protocol.v2.MessageReactionApplied;
import com.fallingnight.chat.protocol.v2.MessageReactionChangedRecord;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageRecord;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.MessageTypeRegistry;
import com.fallingnight.chat.protocol.v2.MessagingPayloadPolicy;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.ReadMessageHistory;
import com.fallingnight.chat.protocol.v2.SubmitMessage;
import com.fallingnight.chat.protocol.v2.SubmitReplyMessage;
import com.fallingnight.chat.protocol.v2.SetMessageReaction;
import com.fallingnight.chat.protocol.v2.SetMessagePin;
import com.fallingnight.chat.protocol.v2.MessagePinApplied;
import com.fallingnight.chat.protocol.v2.MessagePinChangedRecord;
import com.fallingnight.chat.protocol.v2.EditMessage;
import com.fallingnight.chat.protocol.v2.MessageEditApplied;
import com.fallingnight.chat.protocol.v2.MessageEditedRecord;
import com.fallingnight.chat.protocol.v2.ForwardMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Serializes authenticated conversation/message database work off the event loop. */
public final class V2MessagingHandler extends SimpleChannelInboundHandler<Envelope> {
    static final int MAX_PENDING_COMMANDS = 16;

    private final MessageSubmissionPort submissions;
    private final MessageHistoryPort history;
    private final MessageReactionPort reactions;
    private final MessagePinPort pins;
    private final MessageEditPort edits;
    private final MessageForwardPort forwards;
    private final ConversationDirectoryPort directory;
    private final Executor executor;
    private final MessagingEventSink events;
    private final ConversationLiveRouter liveRouter;
    private final Clock clock;
    private final ArrayDeque<Envelope> pending = new ArrayDeque<>();
    private boolean inFlight;

    public V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            Executor executor) {
        this(submissions, history, executor, MessagingEventSink.noop(),
                ConversationLiveRouter.noop(), Clock.systemUTC());
    }

    V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            Executor executor,
            Clock clock) {
        this(submissions, history, executor, MessagingEventSink.noop(),
                ConversationLiveRouter.noop(), clock);
    }

    public V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            Executor executor,
            MessagingEventSink events) {
        this(submissions, history, executor, events,
                ConversationLiveRouter.noop(), Clock.systemUTC());
    }

    public V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            Executor executor,
            MessagingEventSink events) {
        this(submissions, history, directory, executor, events,
                ConversationLiveRouter.noop(), Clock.systemUTC());
    }

    public V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            Executor executor,
            MessagingEventSink events,
            ConversationLiveRouter liveRouter) {
        this(submissions, history, directory, executor, events, liveRouter, Clock.systemUTC());
    }

    V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            Executor executor,
            MessagingEventSink events,
            Clock clock) {
        this(submissions, history, executor, events, ConversationLiveRouter.noop(), clock);
    }

    private V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            Executor executor,
            MessagingEventSink events,
            ConversationLiveRouter liveRouter,
            Clock clock) {
        this(submissions, history, query -> new ConversationDirectoryPage(
                java.util.List.of(), Optional.empty(), false), executor, events,
                liveRouter, command -> MessageReactionResult.Rejected.NOT_AUTHORIZED,
                command -> MessagePinResult.Rejected.NOT_AUTHORIZED,
                command -> MessageEditResult.Rejected.NOT_AUTHORIZED,
                command -> MessageForwardResult.Rejected.NOT_AUTHORIZED, clock);
    }

    V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            Executor executor,
            MessagingEventSink events,
            Clock clock) {
        this(submissions, history, directory, executor, events,
                ConversationLiveRouter.noop(), command ->
                        MessageReactionResult.Rejected.NOT_AUTHORIZED,
                command -> MessagePinResult.Rejected.NOT_AUTHORIZED,
                command -> MessageEditResult.Rejected.NOT_AUTHORIZED,
                command -> MessageForwardResult.Rejected.NOT_AUTHORIZED, clock);
    }

    V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            Executor executor,
            MessagingEventSink events,
            ConversationLiveRouter liveRouter,
            Clock clock) {
        this(submissions, history, directory, executor, events, liveRouter,
                command -> MessageReactionResult.Rejected.NOT_AUTHORIZED,
                command -> MessagePinResult.Rejected.NOT_AUTHORIZED,
                command -> MessageEditResult.Rejected.NOT_AUTHORIZED,
                command -> MessageForwardResult.Rejected.NOT_AUTHORIZED, clock);
    }

    public V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            MessageReactionPort reactions,
            Executor executor,
            MessagingEventSink events,
            ConversationLiveRouter liveRouter) {
        this(submissions, history, directory, executor, events, liveRouter, reactions,
                command -> MessagePinResult.Rejected.NOT_AUTHORIZED,
                command -> MessageEditResult.Rejected.NOT_AUTHORIZED,
                command -> MessageForwardResult.Rejected.NOT_AUTHORIZED, Clock.systemUTC());
    }

    public V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            MessageReactionPort reactions,
            MessagePinPort pins,
            Executor executor,
            MessagingEventSink events,
            ConversationLiveRouter liveRouter) {
        this(submissions, history, directory, executor, events, liveRouter, reactions, pins,
                command -> MessageEditResult.Rejected.NOT_AUTHORIZED,
                command -> MessageForwardResult.Rejected.NOT_AUTHORIZED, Clock.systemUTC());
    }

    public V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            MessageReactionPort reactions,
            MessagePinPort pins,
            MessageEditPort edits,
            Executor executor,
            MessagingEventSink events,
            ConversationLiveRouter liveRouter) {
        this(submissions, history, directory, executor, events, liveRouter, reactions, pins,
                edits, command -> MessageForwardResult.Rejected.NOT_AUTHORIZED,
                Clock.systemUTC());
    }

    public V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            MessageReactionPort reactions,
            MessagePinPort pins,
            MessageEditPort edits,
            MessageForwardPort forwards,
            Executor executor,
            MessagingEventSink events,
            ConversationLiveRouter liveRouter) {
        this(submissions, history, directory, executor, events, liveRouter, reactions, pins,
                edits, forwards, Clock.systemUTC());
    }

    private V2MessagingHandler(
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            Executor executor,
            MessagingEventSink events,
            ConversationLiveRouter liveRouter,
            MessageReactionPort reactions,
            MessagePinPort pins,
            MessageEditPort edits,
            MessageForwardPort forwards,
            Clock clock) {
        this.submissions = Objects.requireNonNull(submissions, "submissions");
        this.history = Objects.requireNonNull(history, "history");
        this.reactions = Objects.requireNonNull(reactions, "reactions");
        this.pins = Objects.requireNonNull(pins, "pins");
        this.edits = Objects.requireNonNull(edits, "edits");
        this.forwards = Objects.requireNonNull(forwards, "forwards");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
        this.liveRouter = Objects.requireNonNull(liveRouter, "liveRouter");
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
                && type != MessageType.MESSAGE_TYPE_SUBMIT_REPLY_MESSAGE
                && type != MessageType.MESSAGE_TYPE_SET_MESSAGE_REACTION
                && type != MessageType.MESSAGE_TYPE_SET_MESSAGE_PIN
                && type != MessageType.MESSAGE_TYPE_EDIT_MESSAGE
                && type != MessageType.MESSAGE_TYPE_FORWARD_MESSAGE
                && type != MessageType.MESSAGE_TYPE_READ_MESSAGE_HISTORY
                && type != MessageType.MESSAGE_TYPE_LIST_CONVERSATIONS) {
            writeError(
                    context,
                    envelope,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE,
                    "unsupported authenticated message type",
                    false);
            return;
        }
        if (type == MessageType.MESSAGE_TYPE_SET_MESSAGE_REACTION
                && !hasReactionCapability(context)) {
            writeError(
                    context,
                    envelope,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE,
                    "message reactions were not negotiated",
                    false);
            return;
        }
        if (type == MessageType.MESSAGE_TYPE_SET_MESSAGE_PIN && !hasPinCapability(context)) {
            writeError(context, envelope,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE,
                    "message pins were not negotiated", false);
            return;
        }
        if (type == MessageType.MESSAGE_TYPE_EDIT_MESSAGE && !hasEditCapability(context)) {
            writeError(context, envelope,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE,
                    "message edits were not negotiated", false);
            return;
        }
        if (type == MessageType.MESSAGE_TYPE_FORWARD_MESSAGE && !hasForwardCapability(context)) {
            writeError(context, envelope,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE,
                    "message forwarding was not negotiated", false);
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
            events.saturated();
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
        liveRouter.unsubscribe(context.channel());
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
            events.saturated();
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
            requireMentionCapability(context, payload.getMentionsCount());
            return new SubmitWork(new MessageSubmission(
                    UUID.fromString(payload.getConversationId()),
                    identity.accountId(),
                    identity.deviceId(),
                    envelope.getClientMessageId(),
                    payload.getContentType(),
                    payload.getContent().toByteArray(), Optional.empty(),
                    mentions(payload.getMentionsList())));
        }
        if (type == MessageType.MESSAGE_TYPE_SUBMIT_REPLY_MESSAGE) {
            SubmitReplyMessage payload = SubmitReplyMessage.parseFrom(envelope.getPayload());
            MessagingPayloadPolicy.requireValid(payload, envelope.getClientMessageId());
            requireMentionCapability(context, payload.getMentionsCount());
            return new SubmitWork(new MessageSubmission(
                    UUID.fromString(payload.getConversationId()),
                    identity.accountId(),
                    identity.deviceId(),
                    envelope.getClientMessageId(),
                    payload.getContentType(),
                    payload.getContent().toByteArray(),
                    Optional.of(UUID.fromString(payload.getTargetMessageId())),
                    mentions(payload.getMentionsList())));
        }
        if (type == MessageType.MESSAGE_TYPE_SET_MESSAGE_REACTION) {
            SetMessageReaction payload = SetMessageReaction.parseFrom(envelope.getPayload());
            MessagingPayloadPolicy.requireValid(payload);
            return new ReactionWork(new MessageReactionCommand(
                    UUID.fromString(payload.getConversationId()),
                    UUID.fromString(payload.getMessageId()),
                    identity.accountId(),
                    identity.deviceId(),
                    reactionKind(payload.getReaction()),
                    payload.getActive(),
                    payload.getClientOperationId()));
        }
        if (type == MessageType.MESSAGE_TYPE_SET_MESSAGE_PIN) {
            SetMessagePin payload = SetMessagePin.parseFrom(envelope.getPayload());
            MessagingPayloadPolicy.requireValid(payload);
            return new PinWork(new MessagePinCommand(
                    UUID.fromString(payload.getConversationId()),
                    UUID.fromString(payload.getMessageId()), identity.accountId(),
                    identity.deviceId(), payload.getPinned(), payload.getClientOperationId()));
        }
        if (type == MessageType.MESSAGE_TYPE_EDIT_MESSAGE) {
            EditMessage payload = EditMessage.parseFrom(envelope.getPayload());
            MessagingPayloadPolicy.requireValid(payload);
            requireMentionCapability(context, payload.getMentionsCount());
            return new EditWork(new MessageEditCommand(
                    UUID.fromString(payload.getConversationId()),
                    UUID.fromString(payload.getMessageId()), identity.accountId(),
                    identity.deviceId(), payload.getExpectedRevision(), payload.getContentType(),
                    payload.getContent().toByteArray(), payload.getClientOperationId(),
                    mentions(payload.getMentionsList())));
        }
        if (type == MessageType.MESSAGE_TYPE_FORWARD_MESSAGE) {
            ForwardMessage payload = ForwardMessage.parseFrom(envelope.getPayload());
            MessagingPayloadPolicy.requireValid(payload, envelope.getClientMessageId());
            return new ForwardWork(new MessageForwardCommand(
                    UUID.fromString(payload.getSourceConversationId()),
                    UUID.fromString(payload.getSourceMessageId()),
                    payload.getExpectedSourceContentRevision(),
                    UUID.fromString(payload.getTargetConversationId()),
                    identity.accountId(), identity.deviceId(), envelope.getClientMessageId()));
        }
        if (type == MessageType.MESSAGE_TYPE_READ_MESSAGE_HISTORY) {
            ReadMessageHistory payload = ReadMessageHistory.parseFrom(envelope.getPayload());
            MessagingPayloadPolicy.requireValid(payload);
            return new HistoryWork(new MessageHistoryQuery(
                    UUID.fromString(payload.getConversationId()),
                    identity.accountId(),
                    payload.getAfterSequence(),
                    payload.getLimit()), hasReactionCapability(context), hasPinCapability(context),
                    hasEditCapability(context), hasMentionCapability(context),
                    hasForwardCapability(context));
        }
        ListConversations payload = ListConversations.parseFrom(envelope.getPayload());
        ConversationPayloadPolicy.requireValid(payload);
        Optional<ConversationDirectoryCursor> after = payload.getAfterConversationId().isEmpty()
                ? Optional.empty()
                : Optional.of(new ConversationDirectoryCursor(
                        Instant.ofEpochMilli(payload.getAfterUpdatedAtEpochMs()),
                        UUID.fromString(payload.getAfterConversationId())));
        return new DirectoryWork(new ConversationDirectoryQuery(
                identity.accountId(), after, payload.getLimit()));
    }

    private void executeOffEventLoop(
            ChannelHandlerContext context, Envelope request, Work work) {
        final Envelope response;
        StoredMessage publication = null;
        MessageReactionResult.Applied reactionPublication = null;
        MessagePinResult.Applied pinPublication = null;
        MessageEditResult.Applied editPublication = null;
        try {
            if (work instanceof SubmitWork submit) {
                MessageSubmissionResult result = submissions.submit(submit.submission());
                response = submitResponse(request, submit.submission(), result);
                publication = publication(submit.submission(), result);
            } else if (work instanceof ReactionWork reaction) {
                MessageReactionResult result = reactions.set(reaction.command());
                response = reactionResponse(request, reaction.command(), result);
                if (result instanceof MessageReactionResult.Applied applied
                        && applied.changed() && !applied.duplicate()) {
                    reactionPublication = applied;
                }
            } else if (work instanceof PinWork pin) {
                MessagePinResult result = pins.set(pin.command());
                response = pinResponse(request, result);
                if (result instanceof MessagePinResult.Applied applied
                        && applied.changed() && !applied.duplicate()) pinPublication = applied;
            } else if (work instanceof EditWork edit) {
                MessageEditResult result = edits.edit(edit.command());
                response = editResponse(request, result);
                if (result instanceof MessageEditResult.Applied applied
                        && applied.changed() && !applied.duplicate()) editPublication = applied;
            } else if (work instanceof ForwardWork forward) {
                MessageForwardResult result = forwards.forward(forward.command());
                response = forwardResponse(request, result);
                if (result instanceof MessageForwardResult.Accepted accepted
                        && !accepted.duplicate()) publication = accepted.message();
            } else if (work instanceof HistoryWork read) {
                response = historyResponse(
                        request, read.query(), liveRouter.readAndSubscribe(
                                context.channel(), read.query(), history),
                        read.reactionsEnabled(), read.pinsEnabled(), read.editsEnabled(),
                        read.mentionsEnabled(), read.forwardingEnabled());
                scheduleHistoryCompletion(context, response, read.query());
                return;
            } else {
                DirectoryWork list = (DirectoryWork) work;
                response = directoryResponse(request, directory.list(list.query()));
            }
        } catch (RuntimeException exception) {
            events.failed();
            scheduleCompletion(context, errorEnvelope(
                    request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR,
                    "messaging is temporarily unavailable",
                    true));
            return;
        }
        scheduleCompletion(context, response, publication, reactionPublication, pinPublication,
                editPublication);
    }

    private void scheduleHistoryCompletion(ChannelHandlerContext context, Envelope response,
            MessageHistoryQuery query) {
        if (context.executor().isShuttingDown()) return;
        try {
            context.executor().execute(() -> {
                if (!context.channel().isActive()) {
                    pending.clear(); return;
                }
                context.writeAndFlush(response).addListener(written -> {
                    if (!written.isSuccess() || !context.channel().isActive()) {
                        pending.clear(); inFlight = false; context.close(); return;
                    }
                    try {
                        executor.execute(() -> activateSubscription(context, query));
                    } catch (RejectedExecutionException exception) {
                        events.saturated(); pending.clear(); inFlight = false; context.close();
                    }
                });
            });
        } catch (RejectedExecutionException exception) {
            pending.clear();
        }
    }

    private void activateSubscription(ChannelHandlerContext context, MessageHistoryQuery query) {
        boolean activated = true;
        try {
            liveRouter.activateSubscription(context.channel(), query, history);
        } catch (RuntimeException exception) {
            activated = false; events.failed();
        }
        boolean succeeded = activated;
        if (context.executor().isShuttingDown()) return;
        try {
            context.executor().execute(() -> {
                inFlight = false;
                if (!succeeded) {
                    pending.clear(); context.close(); return;
                }
                if (context.channel().isActive()) dispatchNext(context);
                else pending.clear();
            });
        } catch (RejectedExecutionException exception) {
            pending.clear(); context.close();
        }
    }

    private void scheduleCompletion(ChannelHandlerContext context, Envelope response) {
        scheduleCompletion(context, response, null, null, null, null);
    }

    private void scheduleCompletion(
            ChannelHandlerContext context, Envelope response, StoredMessage publication) {
        scheduleCompletion(context, response, publication, null, null, null);
    }

    private void scheduleCompletion(
            ChannelHandlerContext context,
            Envelope response,
            StoredMessage publication,
            MessageReactionResult.Applied reactionPublication,
            MessagePinResult.Applied pinPublication,
            MessageEditResult.Applied editPublication) {
        if (context.executor().isShuttingDown()) {
            return;
        }
        try {
            context.executor().execute(() -> {
                inFlight = false;
                if (context.channel().isActive()) {
                    context.writeAndFlush(response);
                    if (publication != null) {
                        try {
                            ConversationLiveRouter.LivePublishResult result =
                                    liveRouter.publish(publication);
                            events.livePublished(result.published());
                            events.liveSlowConsumerClosed(result.slowClosed());
                            events.liveSlowConsumerBacklog(result.maximumBytesBeforeWritable());
                        } catch (RuntimeException exception) {
                            events.failed();
                        }
                    }
                    if (reactionPublication != null) {
                        try {
                            ConversationLiveRouter.LivePublishResult result =
                                    liveRouter.publishReaction(reactionPublication);
                            events.livePublished(result.published());
                            events.liveSlowConsumerClosed(result.slowClosed());
                            events.liveSlowConsumerBacklog(result.maximumBytesBeforeWritable());
                        } catch (RuntimeException exception) {
                            events.failed();
                        }
                    }
                    if (pinPublication != null) {
                        try {
                            ConversationLiveRouter.LivePublishResult result =
                                    liveRouter.publishPin(pinPublication);
                            events.livePublished(result.published());
                            events.liveSlowConsumerClosed(result.slowClosed());
                            events.liveSlowConsumerBacklog(result.maximumBytesBeforeWritable());
                        } catch (RuntimeException exception) { events.failed(); }
                    }
                    if (editPublication != null) {
                        try {
                            ConversationLiveRouter.LivePublishResult result =
                                    liveRouter.publishEdit(editPublication);
                            events.livePublished(result.published());
                            events.liveSlowConsumerClosed(result.slowClosed());
                            events.liveSlowConsumerBacklog(result.maximumBytesBeforeWritable());
                        } catch (RuntimeException exception) { events.failed(); }
                    }
                    dispatchNext(context);
                } else {
                    pending.clear();
                }
            });
        } catch (RejectedExecutionException exception) {
            pending.clear();
        }
    }

    private static StoredMessage publication(
            MessageSubmission submission, MessageSubmissionResult result) {
        if (!(result instanceof MessageSubmissionResult.Accepted accepted) || accepted.duplicate()) {
            return null;
        }
        return new StoredMessage(
                accepted.messageId(),
                submission.conversationId(),
                accepted.conversationSequence(),
                submission.senderAccountId(),
                submission.senderDeviceId(),
                submission.clientMessageId(),
                submission.messageType(),
                submission.payload(),
                accepted.acceptedAt(),
                accepted.reply(), 0, Optional.empty(), submission.mentions());
    }

    private Envelope submitResponse(
            Envelope request,
            MessageSubmission submission,
            MessageSubmissionResult result) {
        if (result == MessageSubmissionResult.Rejected.NOT_AUTHORIZED) {
            events.denied();
            return errorEnvelope(
                    request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                    "not authorized",
                    false);
        }
        if (result == MessageSubmissionResult.Rejected.IDEMPOTENCY_CONFLICT) {
            events.conflict();
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
        events.accepted(accepted.duplicate());
        return responseEnvelope(
                request, MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED, payload.toByteString());
    }

    private Envelope historyResponse(
            Envelope request,
            MessageHistoryQuery query,
            MessageHistoryResult result,
            boolean reactionsEnabled,
            boolean pinsEnabled,
            boolean editsEnabled,
            boolean mentionsEnabled,
            boolean forwardingEnabled) {
        if (result == MessageHistoryResult.Rejected.NOT_AUTHORIZED) {
            events.denied();
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
            payload.addMessages(messageRecord(message, mentionsEnabled, forwardingEnabled));
        }
        for (ConversationHistoryEntry entry : page.entries()) {
            ConversationEntryRecord.Builder encoded = ConversationEntryRecord.newBuilder()
                    .setConversationId(entry.conversationId().toString())
                    .setConversationSequence(entry.conversationSequence());
            if (entry instanceof ConversationHistoryEntry.Message message) {
                encoded.setMessage(messageRecord(
                        message.value(), mentionsEnabled, forwardingEnabled));
            } else if (entry instanceof ConversationHistoryEntry.Recall recall) {
                encoded.setRecall(MessageRecalledRecord.newBuilder()
                        .setConversationId(recall.conversationId().toString())
                        .setConversationSequence(recall.conversationSequence())
                        .setMessageId(recall.messageId().toString())
                        .setActorAccountId(recall.actorAccountId().toString())
                        .setSource(recall.source())
                        .setOccurredAtEpochMs(recall.occurredAt()
                                .map(java.time.Instant::toEpochMilli).orElse(0L)));
            } else if (entry instanceof ConversationHistoryEntry.Deletion deletion) {
                MessagesDeletedRecord.Builder detail = MessagesDeletedRecord.newBuilder()
                        .setConversationId(deletion.conversationId().toString())
                        .setConversationSequence(deletion.conversationSequence())
                        .setActorAccountId(deletion.actorAccountId().toString())
                        .setSource(deletion.source())
                        .setMode(deletion.mode())
                        .setClientOperationId(deletion.clientOperationId())
                        .setCutoffEpochMs(deletion.cutoffEpochMs())
                        .setDeletedCount(deletion.deletedCount())
                        .setOperatorNameSnapshot(deletion.operatorNameSnapshot())
                        .setOccurredAtEpochMs(deletion.occurredAt().toEpochMilli());
                deletion.messageIds().forEach(value -> detail.addMessageIds(value.toString()));
                encoded.setDeletion(detail);
            } else if (entry instanceof ConversationHistoryEntry.Reaction reaction) {
                if (!reactionsEnabled) continue;
                encoded.setReaction(MessageReactionChangedRecord.newBuilder()
                        .setConversationId(reaction.conversationId().toString())
                        .setConversationSequence(reaction.conversationSequence())
                        .setMessageId(reaction.messageId().toString())
                        .setReaction(protocolReaction(reaction.reaction()))
                        .setActive(reaction.active())
                        .setActorAccountId(reaction.actorAccountId().toString())
                        .setClientOperationId(reaction.clientOperationId())
                        .setOccurredAtEpochMs(reaction.occurredAt().toEpochMilli()));
            } else if (entry instanceof ConversationHistoryEntry.Pin pin) {
                if (!pinsEnabled) continue;
                encoded.setPin(MessagePinChangedRecord.newBuilder()
                        .setConversationId(pin.conversationId().toString())
                        .setConversationSequence(pin.conversationSequence())
                        .setMessageId(pin.messageId().toString()).setPinned(pin.pinned())
                        .setActorAccountId(pin.actorAccountId().toString())
                        .setClientOperationId(pin.clientOperationId())
                        .setOccurredAtEpochMs(pin.occurredAt().toEpochMilli()));
            } else if (entry instanceof ConversationHistoryEntry.Edit edit) {
                if (!editsEnabled || edit.contentErased()) continue;
                MessageEditedRecord.Builder editRecord = MessageEditedRecord.newBuilder()
                        .setConversationId(edit.conversationId().toString())
                        .setConversationSequence(edit.conversationSequence())
                        .setMessageId(edit.messageId().toString())
                        .setContentRevision(edit.contentRevision())
                        .setContentType(edit.contentType())
                        .setContent(ByteString.copyFrom(edit.content()))
                        .setActorAccountId(edit.actorAccountId().toString())
                        .setClientOperationId(edit.clientOperationId())
                        .setOccurredAtEpochMs(edit.occurredAt().toEpochMilli());
                if (mentionsEnabled) edit.mentions().forEach(mention ->
                        editRecord.addMentions(protocolMention(mention)));
                encoded.setEdit(editRecord);
            }
            payload.addEntries(encoded);
        }
        MessageHistoryPage built = payload.build();
        MessagingPayloadPolicy.requireValid(built);
        events.historyPage();
        return responseEnvelope(
                request, MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE, built.toByteString());
    }

    private static MessageRecord messageRecord(
            StoredMessage message, boolean mentionsEnabled, boolean forwardingEnabled) {
        MessageRecord.Builder record = MessageRecord.newBuilder()
                .setConversationId(message.conversationId().toString())
                .setMessageId(message.messageId().toString())
                .setConversationSequence(message.conversationSequence())
                .setSenderAccountId(message.senderAccountId().toString())
                .setSenderDeviceId(message.senderDeviceId().toString())
                .setClientMessageId(message.clientMessageId())
                .setContentType(message.messageType())
                .setContent(ByteString.copyFrom(message.payload()))
                .setAcceptedAtEpochMs(message.acceptedAt().toEpochMilli())
                .setContentRevision(message.contentRevision())
                .setEditedAtEpochMs(message.editedAt().map(Instant::toEpochMilli).orElse(0L))
                .setForwarded(forwardingEnabled && message.forwarded());
        message.reply().ifPresent(reply -> record.setReply(
                com.fallingnight.chat.protocol.v2.MessageReplyReference.newBuilder()
                        .setTargetMessageId(reply.targetMessageId().toString())
                        .setTargetConversationSequence(reply.targetConversationSequence())
                        .setTargetSenderAccountId(reply.targetSenderAccountId().toString())));
        if (mentionsEnabled) message.mentions().forEach(mention ->
                record.addMentions(protocolMention(mention)));
        return record.build();
    }

    private Envelope reactionResponse(
            Envelope request,
            MessageReactionCommand command,
            MessageReactionResult result) {
        if (result == MessageReactionResult.Rejected.NOT_AUTHORIZED) {
            events.denied();
            return errorEnvelope(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                    "not authorized", false);
        }
        if (result == MessageReactionResult.Rejected.IDEMPOTENCY_CONFLICT) {
            events.conflict();
            return errorEnvelope(request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_IDEMPOTENCY_CONFLICT,
                    "client operation id conflicts with an accepted reaction", false);
        }
        MessageReactionResult.Applied applied = (MessageReactionResult.Applied) result;
        MessageReactionApplied payload = MessageReactionApplied.newBuilder()
                .setConversationId(applied.conversationId().toString())
                .setMessageId(applied.messageId().toString())
                .setReaction(protocolReaction(applied.reaction()))
                .setActive(applied.active())
                .setActorAccountId(applied.actorAccountId().toString())
                .setClientOperationId(applied.clientOperationId())
                .setChanged(applied.changed())
                .setConversationSequence(applied.conversationSequence())
                .setOccurredAtEpochMs(applied.occurredAt().toEpochMilli())
                .setDuplicate(applied.duplicate())
                .build();
        MessagingPayloadPolicy.requireValid(payload);
        events.reactionApplied(applied.changed(), applied.duplicate());
        return responseEnvelope(
                request, MessageType.MESSAGE_TYPE_MESSAGE_REACTION_APPLIED,
                payload.toByteString());
    }

    private static boolean hasReactionCapability(ChannelHandlerContext context) {
        java.util.Set<ClientCapability> capabilities =
                context.channel().attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
        return capabilities != null && capabilities.contains(
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_REACTIONS);
    }

    private static boolean hasPinCapability(ChannelHandlerContext context) {
        java.util.Set<ClientCapability> capabilities =
                context.channel().attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
        return capabilities != null && capabilities.contains(
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_PINS);
    }

    private static boolean hasEditCapability(ChannelHandlerContext context) {
        java.util.Set<ClientCapability> capabilities =
                context.channel().attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
        return capabilities != null && capabilities.contains(
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_EDITS);
    }

    private static boolean hasMentionCapability(ChannelHandlerContext context) {
        java.util.Set<ClientCapability> capabilities =
                context.channel().attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
        return capabilities != null && capabilities.contains(
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_MENTIONS);
    }

    private static boolean hasForwardCapability(ChannelHandlerContext context) {
        java.util.Set<ClientCapability> capabilities =
                context.channel().attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
        return capabilities != null && capabilities.contains(
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_FORWARDING);
    }

    private static void requireMentionCapability(
            ChannelHandlerContext context, int mentionCount) {
        if (mentionCount > 0 && !hasMentionCapability(context)) {
            throw new IllegalArgumentException("message mentions were not negotiated");
        }
    }

    private static List<MessageMention> mentions(
            List<com.fallingnight.chat.protocol.v2.MessageMention> mentions) {
        return mentions.stream().map(mention -> new MessageMention(
                UUID.fromString(mention.getTargetAccountId()),
                mention.getStartUtf8Byte(), mention.getLengthUtf8Bytes())).toList();
    }

    static com.fallingnight.chat.protocol.v2.MessageMention protocolMention(
            MessageMention mention) {
        return com.fallingnight.chat.protocol.v2.MessageMention.newBuilder()
                .setTargetAccountId(mention.targetAccountId().toString())
                .setStartUtf8Byte(mention.startUtf8Byte())
                .setLengthUtf8Bytes(mention.lengthUtf8Bytes()).build();
    }

    private Envelope editResponse(Envelope request, MessageEditResult result) {
        if (result == MessageEditResult.Rejected.NOT_AUTHORIZED) {
            events.denied();
            return errorEnvelope(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                    "not authorized", false);
        }
        if (result == MessageEditResult.Rejected.IDEMPOTENCY_CONFLICT) {
            events.conflict();
            return errorEnvelope(request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_IDEMPOTENCY_CONFLICT,
                    "client operation id conflicts with an accepted edit", false);
        }
        if (result == MessageEditResult.Rejected.STALE_REVISION) {
            events.conflict();
            return errorEnvelope(request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_MESSAGE_REVISION_CONFLICT,
                    "message revision conflict", false);
        }
        if (result == MessageEditResult.Rejected.WINDOW_EXPIRED) {
            return errorEnvelope(request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_MESSAGE_EDIT_WINDOW_EXPIRED,
                    "message edit window expired", false);
        }
        if (result == MessageEditResult.Rejected.REVISION_LIMIT) {
            return errorEnvelope(request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_MESSAGE_EDIT_REVISION_LIMIT,
                    "message edit revision limit reached", false);
        }
        MessageEditResult.Applied applied = (MessageEditResult.Applied) result;
        MessageEditApplied.Builder payload = MessageEditApplied.newBuilder()
                .setConversationId(applied.conversationId().toString())
                .setMessageId(applied.messageId().toString())
                .setContentRevision(applied.contentRevision())
                .setContentType(applied.contentType())
                .setContent(ByteString.copyFrom(applied.content()))
                .setActorAccountId(applied.actorAccountId().toString())
                .setClientOperationId(applied.clientOperationId())
                .setChanged(applied.changed())
                .setConversationSequence(applied.conversationSequence())
                .setOccurredAtEpochMs(applied.occurredAt().toEpochMilli())
                .setDuplicate(applied.duplicate());
        applied.mentions().forEach(mention -> payload.addMentions(protocolMention(mention)));
        MessageEditApplied built = payload.build();
        MessagingPayloadPolicy.requireValid(built);
        events.editApplied(applied.changed(), applied.duplicate());
        return responseEnvelope(request, MessageType.MESSAGE_TYPE_MESSAGE_EDIT_APPLIED,
                built.toByteString());
    }

    private Envelope forwardResponse(Envelope request, MessageForwardResult result) {
        if (result == MessageForwardResult.Rejected.RATE_LIMITED) {
            events.forwardRateLimited();
            return errorEnvelope(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "message forwarding rate limited", true);
        }
        if (result == MessageForwardResult.Rejected.NOT_AUTHORIZED) {
            events.denied();
            return errorEnvelope(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                    "not authorized", false);
        }
        if (result == MessageForwardResult.Rejected.IDEMPOTENCY_CONFLICT) {
            events.conflict();
            return errorEnvelope(request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_IDEMPOTENCY_CONFLICT,
                    "client message id conflicts with an accepted message", false);
        }
        if (result == MessageForwardResult.Rejected.SOURCE_REVISION_CONFLICT) {
            events.conflict();
            return errorEnvelope(request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_MESSAGE_REVISION_CONFLICT,
                    "message revision conflict", false);
        }
        MessageForwardResult.Accepted accepted = (MessageForwardResult.Accepted) result;
        StoredMessage message = accepted.message();
        MessageAccepted payload = MessageAccepted.newBuilder()
                .setConversationId(message.conversationId().toString())
                .setMessageId(message.messageId().toString())
                .setConversationSequence(message.conversationSequence())
                .setAcceptedAtEpochMs(message.acceptedAt().toEpochMilli())
                .setDuplicate(accepted.duplicate())
                .build();
        MessagingPayloadPolicy.requireValid(payload);
        events.forwardAccepted(accepted.duplicate());
        return responseEnvelope(
                request, MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED, payload.toByteString());
    }

    private Envelope pinResponse(Envelope request, MessagePinResult result) {
        if (result == MessagePinResult.Rejected.NOT_AUTHORIZED) {
            events.denied();
            return errorEnvelope(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                    "not authorized", false);
        }
        if (result == MessagePinResult.Rejected.IDEMPOTENCY_CONFLICT) {
            events.conflict();
            return errorEnvelope(request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_IDEMPOTENCY_CONFLICT,
                    "client operation id conflicts with an accepted pin", false);
        }
        if (result == MessagePinResult.Rejected.LIMIT_REACHED) {
            return errorEnvelope(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "conversation pin limit reached", false);
        }
        MessagePinResult.Applied applied = (MessagePinResult.Applied) result;
        MessagePinApplied payload = MessagePinApplied.newBuilder()
                .setConversationId(applied.conversationId().toString())
                .setMessageId(applied.messageId().toString()).setPinned(applied.pinned())
                .setActorAccountId(applied.actorAccountId().toString())
                .setClientOperationId(applied.clientOperationId()).setChanged(applied.changed())
                .setConversationSequence(applied.conversationSequence())
                .setOccurredAtEpochMs(applied.occurredAt().toEpochMilli())
                .setDuplicate(applied.duplicate()).build();
        MessagingPayloadPolicy.requireValid(payload);
        return responseEnvelope(request, MessageType.MESSAGE_TYPE_MESSAGE_PIN_APPLIED,
                payload.toByteString());
    }

    private static MessageReactionKind reactionKind(
            com.fallingnight.chat.protocol.v2.MessageReactionKind reaction) {
        return switch (reaction) {
            case MESSAGE_REACTION_KIND_LIKE -> MessageReactionKind.LIKE;
            case MESSAGE_REACTION_KIND_LOVE -> MessageReactionKind.LOVE;
            case MESSAGE_REACTION_KIND_LAUGH -> MessageReactionKind.LAUGH;
            case MESSAGE_REACTION_KIND_SURPRISED -> MessageReactionKind.SURPRISED;
            case MESSAGE_REACTION_KIND_SAD -> MessageReactionKind.SAD;
            case MESSAGE_REACTION_KIND_ANGRY -> MessageReactionKind.ANGRY;
            case MESSAGE_REACTION_KIND_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("unsupported reaction");
        };
    }

    static com.fallingnight.chat.protocol.v2.MessageReactionKind protocolReaction(
            MessageReactionKind reaction) {
        return switch (reaction) {
            case LIKE -> com.fallingnight.chat.protocol.v2.MessageReactionKind
                    .MESSAGE_REACTION_KIND_LIKE;
            case LOVE -> com.fallingnight.chat.protocol.v2.MessageReactionKind
                    .MESSAGE_REACTION_KIND_LOVE;
            case LAUGH -> com.fallingnight.chat.protocol.v2.MessageReactionKind
                    .MESSAGE_REACTION_KIND_LAUGH;
            case SURPRISED -> com.fallingnight.chat.protocol.v2.MessageReactionKind
                    .MESSAGE_REACTION_KIND_SURPRISED;
            case SAD -> com.fallingnight.chat.protocol.v2.MessageReactionKind
                    .MESSAGE_REACTION_KIND_SAD;
            case ANGRY -> com.fallingnight.chat.protocol.v2.MessageReactionKind
                    .MESSAGE_REACTION_KIND_ANGRY;
        };
    }

    private Envelope directoryResponse(Envelope request, ConversationDirectoryPage page) {
        com.fallingnight.chat.protocol.v2.ConversationDirectoryPage.Builder payload =
                com.fallingnight.chat.protocol.v2.ConversationDirectoryPage.newBuilder()
                        .setHasMore(page.hasMore());
        for (ConversationSummary summary : page.conversations()) {
            payload.addConversations(ConversationDirectoryRecord.newBuilder()
                    .setConversationId(summary.conversationId().toString())
                    .setKind(switch (summary.kind()) {
                        case DIRECT -> com.fallingnight.chat.protocol.v2.ConversationKind
                                .CONVERSATION_KIND_DIRECT;
                        case GROUP -> com.fallingnight.chat.protocol.v2.ConversationKind
                                .CONVERSATION_KIND_GROUP;
                    })
                    .setDisplayName(summary.displayName())
                    .setRole(switch (summary.role()) {
                        case OWNER -> com.fallingnight.chat.protocol.v2.ConversationRole
                                .CONVERSATION_ROLE_OWNER;
                        case ADMIN -> com.fallingnight.chat.protocol.v2.ConversationRole
                                .CONVERSATION_ROLE_ADMIN;
                        case MEMBER -> com.fallingnight.chat.protocol.v2.ConversationRole
                                .CONVERSATION_ROLE_MEMBER;
                    })
                    .setLatestSequence(summary.latestSequence())
                    .setLastReadSequence(summary.lastReadSequence())
                    .setUpdatedAtEpochMs(summary.updatedAt().toEpochMilli()));
        }
        page.next().ifPresent(cursor -> payload
                .setNextUpdatedAtEpochMs(cursor.updatedAt().toEpochMilli())
                .setNextConversationId(cursor.conversationId().toString()));
        com.fallingnight.chat.protocol.v2.ConversationDirectoryPage built = payload.build();
        ConversationPayloadPolicy.requireValid(built);
        events.directoryPage();
        return responseEnvelope(request,
                MessageType.MESSAGE_TYPE_CONVERSATION_DIRECTORY_PAGE, built.toByteString());
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

    private record HistoryWork(
            MessageHistoryQuery query, boolean reactionsEnabled, boolean pinsEnabled,
            boolean editsEnabled, boolean mentionsEnabled, boolean forwardingEnabled)
            implements Work {}

    private record ReactionWork(MessageReactionCommand command) implements Work {}

    private record PinWork(MessagePinCommand command) implements Work {}

    private record EditWork(MessageEditCommand command) implements Work {}

    private record ForwardWork(MessageForwardCommand command) implements Work {}

    private record DirectoryWork(ConversationDirectoryQuery query) implements Work {}
}
