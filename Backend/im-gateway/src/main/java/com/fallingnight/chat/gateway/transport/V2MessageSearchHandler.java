package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.messaging.MessageSearchPort;
import com.fallingnight.chat.application.messaging.MessageSearchQuery;
import com.fallingnight.chat.application.messaging.MessageSearchResult;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.ConversationMessageSearchPage;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.MessageTypeRegistry;
import com.fallingnight.chat.protocol.v2.MessagingPayloadPolicy;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.SearchConversationMessages;
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

/** Capability-gated, serialized message-search work off the event loop. */
public final class V2MessageSearchHandler extends SimpleChannelInboundHandler<Envelope> {
    static final int MAX_PENDING_COMMANDS = 8;

    private final MessageSearchPort search;
    private final Executor executor;
    private final MessagingEventSink events;
    private final Clock clock;
    private final ArrayDeque<Envelope> pending = new ArrayDeque<>();
    private boolean inFlight;

    public V2MessageSearchHandler(
            MessageSearchPort search, Executor executor, MessagingEventSink events) {
        this(search, executor, events, Clock.systemUTC());
    }

    V2MessageSearchHandler(MessageSearchPort search, Executor executor,
            MessagingEventSink events, Clock clock) {
        this.search = Objects.requireNonNull(search, "search");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean acceptInboundMessage(Object message) {
        if (!(message instanceof Envelope envelope)) return false;
        return MessageTypeRegistry.find(envelope.getMessageType()).orElse(null)
                == MessageType.MESSAGE_TYPE_SEARCH_CONVERSATION_MESSAGES;
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
                || !capabilities.contains(ClientCapability.CLIENT_CAPABILITY_MESSAGE_SEARCH)) {
            writeError(context, request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE,
                    "message search was not negotiated", false);
            return;
        }
        if (request.getKind() != MessageKind.MESSAGE_KIND_COMMAND) {
            invalid(context, request);
            return;
        }
        if (pending.size() >= MAX_PENDING_COMMANDS) {
            events.saturated();
            writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "too many pending message searches", true);
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
        final MessageSearchQuery query;
        try {
            SearchConversationMessages payload =
                    SearchConversationMessages.parseFrom(request.getPayload());
            MessagingPayloadPolicy.requireValid(payload);
            AuthenticatedConnection identity = Objects.requireNonNull(
                    context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get());
            query = new MessageSearchQuery(
                    UUID.fromString(payload.getConversationId()), identity.accountId(),
                    payload.getLiteralQuery(), payload.getBeforeSequence(), payload.getLimit());
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
                    "message search is busy", true);
            dispatchNext(context);
        }
    }

    private void execute(ChannelHandlerContext context, Envelope request,
            MessageSearchQuery query) {
        Envelope response;
        try {
            MessageSearchResult result = search.search(query);
            if (result == MessageSearchResult.Rejected.NOT_AUTHORIZED) {
                events.denied();
                response = error(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                        "not authorized", false);
            } else {
                response = page(context, request, ((MessageSearchResult.Found) result).page());
                events.searchPage();
            }
        } catch (RuntimeException exception) {
            events.failed();
            response = error(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR,
                    "message search is temporarily unavailable", true);
        }
        scheduleCompletion(context, response);
    }

    private Envelope page(ChannelHandlerContext context, Envelope request,
            com.fallingnight.chat.application.messaging.MessageSearchPage result) {
        Set<ClientCapability> capabilities =
                context.channel().attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
        boolean mentions = capabilities != null && capabilities.contains(
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_MENTIONS);
        boolean forwarding = capabilities != null && capabilities.contains(
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_FORWARDING);
        ConversationMessageSearchPage.Builder payload =
                ConversationMessageSearchPage.newBuilder()
                        .setConversationId(result.conversationId().toString())
                        .setNextBeforeSequence(result.nextBeforeSequence())
                        .setHasMore(result.hasMore());
        result.hits().forEach(hit -> payload.addHits(
                V2MessagingHandler.messageRecord(hit, mentions, forwarding)));
        ConversationMessageSearchPage built = payload.build();
        MessagingPayloadPolicy.requireValid(built);
        return envelope(request, MessageKind.MESSAGE_KIND_RESPONSE,
                MessageType.MESSAGE_TYPE_CONVERSATION_MESSAGE_SEARCH_PAGE,
                built.toByteString());
    }

    private void invalid(ChannelHandlerContext context, Envelope request) {
        writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                "invalid message search payload", false);
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
