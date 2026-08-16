package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.contact.AccountBlockIntent;
import com.fallingnight.chat.application.contact.AccountBlockDirectoryRequest;
import com.fallingnight.chat.application.contact.AccountBlockDirectoryResult;
import com.fallingnight.chat.application.contact.AccountBlockDirectoryUseCase;
import com.fallingnight.chat.application.contact.AccountBlockResult;
import com.fallingnight.chat.application.contact.AccountBlockUseCase;
import com.fallingnight.chat.protocol.v2.AccountBlockApplied;
import com.fallingnight.chat.protocol.v2.AccountBlockDirectoryPage;
import com.fallingnight.chat.protocol.v2.AccountBlockSummary;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.ContactPayloadPolicy;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.MessageTypeRegistry;
import com.fallingnight.chat.protocol.v2.ListAccountBlocks;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.SetAccountBlock;
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

/** Capability-gated, serialized account-block mutation/directory work off the event loop. */
public final class V2AccountBlockHandler extends SimpleChannelInboundHandler<Envelope> {
    static final int MAX_PENDING_COMMANDS = 8;

    private final AccountBlockUseCase blocks;
    private final AccountBlockDirectoryUseCase directory;
    private final Executor executor;
    private final MessagingEventSink events;
    private final Clock clock;
    private final ArrayDeque<Envelope> pending = new ArrayDeque<>();
    private boolean inFlight;

    public V2AccountBlockHandler(
            AccountBlockUseCase blocks, Executor executor, MessagingEventSink events) {
        this(blocks, unavailableDirectory(), executor, events, Clock.systemUTC());
    }

    public V2AccountBlockHandler(AccountBlockUseCase blocks,
            AccountBlockDirectoryUseCase directory, Executor executor,
            MessagingEventSink events) {
        this(blocks, directory, executor, events, Clock.systemUTC());
    }

    V2AccountBlockHandler(AccountBlockUseCase blocks, Executor executor,
            MessagingEventSink events, Clock clock) {
        this(blocks, unavailableDirectory(), executor, events, clock);
    }

    V2AccountBlockHandler(AccountBlockUseCase blocks, AccountBlockDirectoryUseCase directory,
            Executor executor, MessagingEventSink events, Clock clock) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean acceptInboundMessage(Object message) {
        if (!(message instanceof Envelope envelope)) return false;
        MessageType type = MessageTypeRegistry.find(envelope.getMessageType()).orElse(null);
        return type == MessageType.MESSAGE_TYPE_SET_ACCOUNT_BLOCK
                || type == MessageType.MESSAGE_TYPE_LIST_ACCOUNT_BLOCKS;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, Envelope request) {
        AuthenticatedConnection identity =
                context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get();
        if (identity == null) {
            writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "authentication is required", false);
            return;
        }
        Set<ClientCapability> capabilities =
                context.channel().attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
        if (capabilities == null || !capabilities.contains(
                ClientCapability.CLIENT_CAPABILITY_ACCOUNT_BLOCKING)) {
            writeError(context, request,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE,
                    "account blocking was not negotiated", false);
            return;
        }
        if (request.getKind() != MessageKind.MESSAGE_KIND_COMMAND) {
            invalid(context, request);
            return;
        }
        if (pending.size() >= MAX_PENDING_COMMANDS) {
            events.saturated();
            writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "too many pending account operations", true);
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
        MessageType type = MessageTypeRegistry.find(request.getMessageType()).orElseThrow();
        if (type == MessageType.MESSAGE_TYPE_LIST_ACCOUNT_BLOCKS) {
            dispatchDirectory(context, request);
            return;
        }
        final UUID actor;
        final AccountBlockIntent intent;
        try {
            SetAccountBlock payload = SetAccountBlock.parseFrom(request.getPayload());
            ContactPayloadPolicy.requireValid(payload);
            AuthenticatedConnection identity = Objects.requireNonNull(
                    context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get());
            actor = identity.accountId();
            intent = new AccountBlockIntent(
                    UUID.fromString(payload.getTargetAccountId()), payload.getBlocked(),
                    UUID.fromString(payload.getClientOperationId()));
        } catch (Exception exception) {
            invalid(context, request);
            dispatchNext(context);
            return;
        }
        inFlight = true;
        try {
            executor.execute(() -> execute(context, request, actor, intent));
        } catch (RejectedExecutionException exception) {
            inFlight = false;
            events.saturated();
            writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "account service is busy", true);
            dispatchNext(context);
        }
    }

    private void dispatchDirectory(ChannelHandlerContext context, Envelope request) {
        final UUID actor;
        final AccountBlockDirectoryRequest query;
        try {
            ListAccountBlocks payload = ListAccountBlocks.parseFrom(request.getPayload());
            ContactPayloadPolicy.requireValid(payload);
            AuthenticatedConnection identity = Objects.requireNonNull(
                    context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get());
            actor = identity.accountId();
            query = new AccountBlockDirectoryRequest(
                    payload.getAfterTargetAccountId().isEmpty()
                            ? Optional.empty()
                            : Optional.of(UUID.fromString(payload.getAfterTargetAccountId())),
                    payload.getLimit());
        } catch (Exception exception) {
            invalid(context, request);
            dispatchNext(context);
            return;
        }
        inFlight = true;
        try {
            executor.execute(() -> executeDirectory(context, request, actor, query));
        } catch (RejectedExecutionException exception) {
            inFlight = false;
            events.saturated();
            writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "account service is busy", true);
            dispatchNext(context);
        }
    }

    private void execute(ChannelHandlerContext context, Envelope request,
            UUID actor, AccountBlockIntent intent) {
        Envelope response;
        try {
            AccountBlockResult result = blocks.apply(actor, intent);
            if (result instanceof AccountBlockResult.Applied applied) {
                AccountBlockApplied payload = AccountBlockApplied.newBuilder()
                        .setActorAccountId(applied.actorAccountId().toString())
                        .setTargetAccountId(applied.targetAccountId().toString())
                        .setBlocked(applied.blocked()).setChanged(applied.changed())
                        .setClientOperationId(applied.clientOperationId().toString()).build();
                ContactPayloadPolicy.requireValid(payload);
                events.accountBlockApplied(applied.changed());
                response = envelope(request, MessageKind.MESSAGE_KIND_RESPONSE,
                        MessageType.MESSAGE_TYPE_ACCOUNT_BLOCK_APPLIED, payload.toByteString());
            } else if (result instanceof AccountBlockResult.OperationConflict) {
                events.conflict();
                response = error(request,
                        ProtocolErrorCode.PROTOCOL_ERROR_CODE_IDEMPOTENCY_CONFLICT,
                        "account operation conflicts with an earlier request", false);
            } else {
                events.denied();
                response = error(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                        "account operation is not available", false);
            }
        } catch (RuntimeException exception) {
            events.failed();
            response = error(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR,
                    "account service is temporarily unavailable", true);
        }
        scheduleCompletion(context, response);
    }

    private void executeDirectory(ChannelHandlerContext context, Envelope request,
            UUID actor, AccountBlockDirectoryRequest query) {
        Envelope response;
        try {
            AccountBlockDirectoryResult result = directory.list(actor, query);
            if (result instanceof AccountBlockDirectoryResult.Found found) {
                var page = found.page();
                var payload = AccountBlockDirectoryPage.newBuilder()
                        .setHasMore(page.hasMore());
                for (var block : page.blocks()) {
                    payload.addBlocks(AccountBlockSummary.newBuilder()
                            .setTargetAccountId(block.targetAccountId().toString())
                            .setTargetDisplayName(block.targetDisplayName())
                            .setBlockedAtEpochMs(block.blockedAt().toEpochMilli()));
                }
                page.nextAfterTargetAccountId().ifPresent(cursor ->
                        payload.setNextAfterTargetAccountId(cursor.toString()));
                AccountBlockDirectoryPage built = payload.build();
                ContactPayloadPolicy.requireValid(built);
                events.accountBlockDirectoryPage(built.getBlocksCount());
                response = envelope(request, MessageKind.MESSAGE_KIND_RESPONSE,
                        MessageType.MESSAGE_TYPE_ACCOUNT_BLOCK_DIRECTORY_PAGE,
                        built.toByteString());
            } else {
                events.denied();
                response = error(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                        "account directory is not available", false);
            }
        } catch (RuntimeException exception) {
            events.failed();
            response = error(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR,
                    "account service is temporarily unavailable", true);
        }
        scheduleCompletion(context, response);
    }

    private void invalid(ChannelHandlerContext context, Envelope request) {
        writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                "invalid account block payload", false);
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

    private static AccountBlockDirectoryUseCase unavailableDirectory() {
        return (actor, request) -> AccountBlockDirectoryResult.Rejected.NOT_AUTHORIZED;
    }
}
