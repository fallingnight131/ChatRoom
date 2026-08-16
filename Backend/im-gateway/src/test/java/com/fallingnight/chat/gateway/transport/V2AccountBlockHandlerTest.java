package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.contact.AccountBlockIntent;
import com.fallingnight.chat.application.contact.AccountBlockResult;
import com.fallingnight.chat.application.contact.AccountBlockUseCase;
import com.fallingnight.chat.protocol.v2.AccountBlockApplied;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.SetAccountBlock;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V2AccountBlockHandlerTest {
    private static final UUID ACCOUNT =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID TARGET =
            UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID OPERATION =
            UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID DEVICE =
            UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID SESSION =
            UUID.fromString("50000000-0000-4000-8000-000000000005");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-17T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void bindsAuthenticatedActorAndReturnsCorrelatedDurableResult() throws Exception {
        AtomicReference<UUID> actor = new AtomicReference<>();
        AtomicReference<AccountBlockIntent> intent = new AtomicReference<>();
        MessagingTelemetry telemetry = new MessagingTelemetry();
        EmbeddedChannel channel = channel((authenticated, command) -> {
            actor.set(authenticated);
            intent.set(command);
            return new AccountBlockResult.Applied(
                    authenticated, command.targetAccountId(), command.blocked(), true,
                    command.clientOperationId());
        }, Runnable::run, true, telemetry);
        try {
            channel.writeInbound(command(TARGET.toString(), true, OPERATION.toString()));
            channel.runPendingTasks();
            Envelope response = channel.readOutbound();
            AccountBlockApplied applied = AccountBlockApplied.parseFrom(response.getPayload());
            assertEquals(MessageType.MESSAGE_TYPE_ACCOUNT_BLOCK_APPLIED_VALUE,
                    response.getMessageType());
            assertEquals(ACCOUNT, actor.get());
            assertEquals(TARGET, intent.get().targetAccountId());
            assertEquals(ACCOUNT.toString(), applied.getActorAccountId());
            assertEquals(TARGET.toString(), applied.getTargetAccountId());
            assertEquals(OPERATION.toString(), applied.getClientOperationId());
            assertTrue(applied.getBlocked());
            assertTrue(applied.getChanged());
            assertEquals(1, telemetry.snapshot().accountBlockChanged());
            assertEquals(0, telemetry.snapshot().accountBlockNoOp());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void mapsCapabilityPayloadPrivacyConflictAndBusyFailuresSafely() throws Exception {
        EmbeddedChannel uncapable = channel((actor, intent) -> {
            throw new AssertionError();
        }, Runnable::run, false);
        try {
            uncapable.writeInbound(command(TARGET.toString(), true, OPERATION.toString()));
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE,
                    error(uncapable).getCode());
        } finally {
            uncapable.finishAndReleaseAll();
        }

        EmbeddedChannel capable = channel((actor, intent) ->
                AccountBlockResult.Rejected.TARGET_UNAVAILABLE, Runnable::run, true);
        try {
            capable.writeInbound(command("bad", true, OPERATION.toString()));
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    error(capable).getCode());
            capable.writeInbound(command(TARGET.toString(), true, OPERATION.toString()));
            capable.runPendingTasks();
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                    error(capable).getCode());
        } finally {
            capable.finishAndReleaseAll();
        }

        EmbeddedChannel conflict = channel((actor, intent) ->
                new AccountBlockResult.OperationConflict(intent.clientOperationId()),
                Runnable::run, true);
        try {
            conflict.writeInbound(command(TARGET.toString(), false, OPERATION.toString()));
            conflict.runPendingTasks();
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_IDEMPOTENCY_CONFLICT,
                    error(conflict).getCode());
        } finally {
            conflict.finishAndReleaseAll();
        }

        Executor rejected = task -> { throw new RejectedExecutionException(); };
        EmbeddedChannel busy = channel((actor, intent) -> { throw new AssertionError(); },
                rejected, true);
        try {
            busy.writeInbound(command(TARGET.toString(), true, OPERATION.toString()));
            ProtocolError error = error(busy);
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED, error.getCode());
            assertTrue(error.getRetryable());
        } finally {
            busy.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel(
            AccountBlockUseCase useCase, Executor executor, boolean capable) {
        return channel(useCase, executor, capable, MessagingEventSink.noop());
    }

    private static EmbeddedChannel channel(AccountBlockUseCase useCase, Executor executor,
            boolean capable, MessagingEventSink events) {
        EmbeddedChannel channel = new EmbeddedChannel(
                new V2AccountBlockHandler(useCase, executor, events, CLOCK));
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT, DEVICE, SESSION));
        channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(capable
                ? Set.of(ClientCapability.CLIENT_CAPABILITY_ACCOUNT_BLOCKING) : Set.of());
        return channel;
    }

    private static Envelope command(String target, boolean blocked, String operation) {
        SetAccountBlock payload = SetAccountBlock.newBuilder().setTargetAccountId(target)
                .setBlocked(blocked).setClientOperationId(operation).build();
        return Envelope.newBuilder().setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .setMessageType(MessageType.MESSAGE_TYPE_SET_ACCOUNT_BLOCK_VALUE)
                .setRequestId("request-1").setSessionId(SESSION.toString())
                .setPayload(payload.toByteString()).build();
    }

    private static ProtocolError error(EmbeddedChannel channel) throws Exception {
        Envelope response = channel.readOutbound();
        assertFalse(response.getPayload().isEmpty());
        return ProtocolError.parseFrom(response.getPayload());
    }
}
