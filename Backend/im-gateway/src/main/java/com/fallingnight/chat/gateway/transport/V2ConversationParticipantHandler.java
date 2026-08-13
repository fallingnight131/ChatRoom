package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.conversation.ConversationParticipant;
import com.fallingnight.chat.application.conversation.ConversationParticipantPort;
import com.fallingnight.chat.application.conversation.ConversationParticipantQuery;
import com.fallingnight.chat.application.conversation.ConversationParticipantResult;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.ConversationParticipantPage;
import com.fallingnight.chat.protocol.v2.ConversationParticipantRecord;
import com.fallingnight.chat.protocol.v2.ConversationPayloadPolicy;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.ListConversationParticipants;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.MessageTypeRegistry;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.google.protobuf.ByteString;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Capability-gated, serialized participant-directory work off the event loop. */
public final class V2ConversationParticipantHandler extends SimpleChannelInboundHandler<Envelope> {
    static final int MAX_PENDING_COMMANDS = 8;

    private final ConversationParticipantPort participants;
    private final Executor executor;
    private final MessagingEventSink events;
    private final Clock clock;
    private final ArrayDeque<Envelope> pending = new ArrayDeque<>();
    private boolean inFlight;

    public V2ConversationParticipantHandler(ConversationParticipantPort participants,
            Executor executor, MessagingEventSink events) {
        this(participants, executor, events, Clock.systemUTC());
    }

    V2ConversationParticipantHandler(ConversationParticipantPort participants,
            Executor executor, MessagingEventSink events, Clock clock) {
        this.participants = Objects.requireNonNull(participants, "participants");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean acceptInboundMessage(Object message) {
        if (!(message instanceof Envelope envelope)) return false;
        return MessageTypeRegistry.find(envelope.getMessageType()).orElse(null)
                == MessageType.MESSAGE_TYPE_LIST_CONVERSATION_PARTICIPANTS;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, Envelope request) {
        if (context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get() == null) {
            writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "authentication is required", false);
            return;
        }
        Set<ClientCapability> capabilities =
                context.channel().attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
        if (capabilities == null
                || !capabilities.contains(ClientCapability.CLIENT_CAPABILITY_MESSAGE_MENTIONS)) {
            writeError(context, request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE,
                    "message mentions were not negotiated", false);
            return;
        }
        if (request.getKind() != MessageKind.MESSAGE_KIND_COMMAND) {
            invalid(context, request);
            return;
        }
        if (pending.size() >= MAX_PENDING_COMMANDS) {
            events.saturated();
            writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "too many pending participant queries", true);
            return;
        }
        pending.addLast(request);
        dispatchNext(context);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        pending.clear();
        context.fireChannelInactive();
    }

    private void dispatchNext(ChannelHandlerContext context) {
        if (inFlight || pending.isEmpty() || !context.channel().isActive()) return;
        Envelope request = pending.removeFirst();
        final ConversationParticipantQuery query;
        try {
            ListConversationParticipants payload =
                    ListConversationParticipants.parseFrom(request.getPayload());
            ConversationPayloadPolicy.requireValid(payload);
            AuthenticatedConnection identity = Objects.requireNonNull(
                    context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get());
            Optional<UUID> after = payload.getAfterAccountId().isEmpty()
                    ? Optional.empty() : Optional.of(UUID.fromString(payload.getAfterAccountId()));
            query = new ConversationParticipantQuery(
                    UUID.fromString(payload.getConversationId()), identity.accountId(), after,
                    payload.getLimit());
        } catch (Exception exception) {
            invalid(context, request);
            dispatchNext(context);
            return;
        }
        inFlight = true;
        try {
            executor.execute(() -> execute(context, request, query));
        } catch (RejectedExecutionException exception) {
            inFlight = false;
            events.saturated();
            writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "participant directory is busy", true);
            dispatchNext(context);
        }
    }

    private void execute(ChannelHandlerContext context, Envelope request,
            ConversationParticipantQuery query) {
        Envelope response;
        try {
            ConversationParticipantResult result = participants.list(query);
            if (result == ConversationParticipantResult.Rejected.NOT_AUTHORIZED) {
                events.denied();
                response = error(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                        "not authorized", false);
            } else {
                response = page(request,
                        ((ConversationParticipantResult.Found) result).page());
                events.directoryPage();
            }
        } catch (RuntimeException exception) {
            events.failed();
            response = error(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR,
                    "participant directory is temporarily unavailable", true);
        }
        scheduleCompletion(context, response);
    }

    private Envelope page(Envelope request,
            com.fallingnight.chat.application.conversation.ConversationParticipantPage result) {
        ConversationParticipantPage.Builder payload = ConversationParticipantPage.newBuilder()
                .setConversationId(result.conversationId().toString())
                .setNextAccountId(result.nextAccountId().map(UUID::toString).orElse(""))
                .setHasMore(result.hasMore());
        for (ConversationParticipant participant : result.participants()) {
            payload.addParticipants(ConversationParticipantRecord.newBuilder()
                    .setAccountId(participant.accountId().toString())
                    .setDisplayName(participant.displayName())
                    .setRole(switch (participant.role()) {
                        case OWNER -> com.fallingnight.chat.protocol.v2.ConversationRole
                                .CONVERSATION_ROLE_OWNER;
                        case ADMIN -> com.fallingnight.chat.protocol.v2.ConversationRole
                                .CONVERSATION_ROLE_ADMIN;
                        case MEMBER -> com.fallingnight.chat.protocol.v2.ConversationRole
                                .CONVERSATION_ROLE_MEMBER;
                    }));
        }
        ConversationParticipantPage built = payload.build();
        ConversationPayloadPolicy.requireValid(built);
        return envelope(request, MessageKind.MESSAGE_KIND_RESPONSE,
                MessageType.MESSAGE_TYPE_CONVERSATION_PARTICIPANT_PAGE, built.toByteString());
    }

    private void invalid(ChannelHandlerContext context, Envelope request) {
        writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                "invalid participant directory payload", false);
    }

    private void scheduleCompletion(ChannelHandlerContext context, Envelope response) {
        if (context.executor().isShuttingDown()) return;
        try {
            context.executor().execute(() -> {
                inFlight = false;
                if (context.channel().isActive()) {
                    context.writeAndFlush(response);
                    dispatchNext(context);
                } else pending.clear();
            });
        } catch (RejectedExecutionException exception) {
            pending.clear();
        }
    }

    private void writeError(ChannelHandlerContext context, Envelope request,
            ProtocolErrorCode code, String message, boolean retryable) {
        context.writeAndFlush(error(request, code, message, retryable));
    }

    private Envelope error(Envelope request, ProtocolErrorCode code,
            String message, boolean retryable) {
        ProtocolError payload = ProtocolError.newBuilder().setCode(code)
                .setSafeMessage(message).setRetryable(retryable).build();
        return envelope(request, MessageKind.MESSAGE_KIND_ERROR,
                MessageType.MESSAGE_TYPE_PROTOCOL_ERROR, payload.toByteString());
    }

    private Envelope envelope(Envelope request, MessageKind kind, MessageType type,
            ByteString payload) {
        return Envelope.newBuilder().setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(kind).setMessageType(type.getNumber()).setRequestId(request.getRequestId())
                .setSessionId(request.getSessionId()).setClientMessageId(request.getClientMessageId())
                .setSentAtEpochMs(clock.millis()).setPayload(payload).build();
    }
}
