package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.identity.ClientPlatform;
import com.fallingnight.chat.application.identity.DeviceDirectoryResult;
import com.fallingnight.chat.application.identity.DeviceManagementPort;
import com.fallingnight.chat.application.identity.DeviceManagementService;
import com.fallingnight.chat.application.identity.DeviceRevocationResult;
import com.fallingnight.chat.application.identity.ManagedDevice;
import com.fallingnight.chat.protocol.v2.DeviceDirectory;
import com.fallingnight.chat.protocol.v2.DeviceRevoked;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.ListDevices;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.RevokeDevice;
import com.google.protobuf.ByteString;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V2DeviceManagementHandlerTest {
    private static final UUID ACCOUNT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID TARGET = UUID.fromString("20000000-0000-4000-8000-000000000003");
    private static final UUID SESSION = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID AUDIT = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void listsOnlyServiceProjectionUsingServerBoundActor() throws Exception {
        AtomicReference<com.fallingnight.chat.application.identity.AuthenticatedDeviceActor>
                captured = new AtomicReference<>();
        DeviceManagementPort port = new StubPort() {
            @Override public DeviceDirectoryResult listActive(
                    com.fallingnight.chat.application.identity.AuthenticatedDeviceActor actor) {
                captured.set(actor);
                return new DeviceDirectoryResult.Available(List.of(
                        new ManagedDevice(ACTOR, ClientPlatform.WEB, NOW.minusSeconds(2),
                                NOW.minusSeconds(1), true),
                        new ManagedDevice(TARGET, ClientPlatform.WINDOWS, NOW.minusSeconds(3),
                                NOW, false)));
            }
        };
        EmbeddedChannel channel = channel(port, Runnable::run, new DeviceConnectionRegistry(), true);
        try {
            channel.writeInbound(command(MessageType.MESSAGE_TYPE_LIST_DEVICES,
                    ListDevices.getDefaultInstance().toByteString()));
            channel.runPendingTasks();
            Envelope response = channel.readOutbound();
            DeviceDirectory directory = DeviceDirectory.parseFrom(response.getPayload());
            assertEquals(ACCOUNT, captured.get().accountId());
            assertEquals(ACTOR, captured.get().deviceId());
            assertEquals(SESSION, captured.get().sessionId());
            assertEquals(2, directory.getDevicesCount());
            assertTrue(directory.getDevices(0).getCurrent());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void revokeReturnsExactResultAndClosesEveryTargetConnection() throws Exception {
        DeviceConnectionRegistry registry = new DeviceConnectionRegistry();
        EmbeddedChannel targetOne = tracked(registry, TARGET, UUID.randomUUID());
        EmbeddedChannel targetTwo = tracked(registry, TARGET, UUID.randomUUID());
        DeviceManagementPort port = new StubPort() {
            @Override public DeviceRevocationResult revokeOther(
                    com.fallingnight.chat.application.identity.AuthenticatedDeviceActor actor,
                    UUID target) {
                assertEquals(TARGET, target);
                return new DeviceRevocationResult.Revoked(TARGET, AUDIT, NOW, 2, true);
            }
        };
        EmbeddedChannel actor = channel(port, Runnable::run, registry, true);
        try {
            actor.writeInbound(command(MessageType.MESSAGE_TYPE_REVOKE_DEVICE,
                    RevokeDevice.newBuilder().setTargetDeviceId(TARGET.toString())
                            .build().toByteString()));
            actor.runPendingTasks();
            DeviceRevoked response = DeviceRevoked.parseFrom(
                    ((Envelope) actor.readOutbound()).getPayload());
            assertEquals(2, response.getRevokedSessions());
            assertTrue(response.getChanged());
            assertFalse(targetOne.isActive());
            assertFalse(targetTwo.isActive());
            assertTrue(actor.isActive());
        } finally {
            actor.finishAndReleaseAll();
            targetOne.finishAndReleaseAll();
            targetTwo.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsUnauthenticatedMalformedDeniedAndSaturatedSafely() throws Exception {
        EmbeddedChannel channel = channel(new StubPort(), Runnable::run,
                new DeviceConnectionRegistry(), false);
        try {
            channel.writeInbound(command(MessageType.MESSAGE_TYPE_LIST_DEVICES, ByteString.EMPTY));
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE, error(channel).getCode());
            authenticate(channel);
            channel.writeInbound(command(MessageType.MESSAGE_TYPE_REVOKE_DEVICE,
                    RevokeDevice.newBuilder().setTargetDeviceId("foreign").build().toByteString()));
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD, error(channel).getCode());
            channel.writeInbound(command(MessageType.MESSAGE_TYPE_LIST_DEVICES, ByteString.EMPTY));
            channel.runPendingTasks();
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED, error(channel).getCode());
        } finally {
            channel.finishAndReleaseAll();
        }

        Executor rejected = command -> { throw new RejectedExecutionException(); };
        EmbeddedChannel saturated = channel(new StubPort(), rejected,
                new DeviceConnectionRegistry(), true);
        try {
            saturated.writeInbound(command(MessageType.MESSAGE_TYPE_LIST_DEVICES, ByteString.EMPTY));
            ProtocolError result = error(saturated);
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED, result.getCode());
            assertTrue(result.getRetryable());
        } finally {
            saturated.finishAndReleaseAll();
        }
    }

    @Test
    void boundsPendingCommandsWhileOneIsHeld() {
        HoldingExecutor holding = new HoldingExecutor();
        EmbeddedChannel channel = channel(new StubPort(), holding,
                new DeviceConnectionRegistry(), true);
        try {
            for (int i = 0; i < V2DeviceManagementHandler.MAX_PENDING_COMMANDS + 2; i++) {
                channel.writeInbound(command(MessageType.MESSAGE_TYPE_LIST_DEVICES, ByteString.EMPTY));
            }
            ProtocolError saturated = error(channel);
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED, saturated.getCode());
            assertTrue(saturated.getRetryable());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel(DeviceManagementPort port, Executor executor,
            DeviceConnectionRegistry registry, boolean authenticated) {
        EmbeddedChannel channel = new EmbeddedChannel(new V2DeviceManagementHandler(
                new DeviceManagementService(port), executor, registry,
                DeviceManagementEventSink.noop(), CLOCK));
        if (authenticated) authenticate(channel);
        return channel;
    }

    private static EmbeddedChannel tracked(DeviceConnectionRegistry registry,
            UUID deviceId, UUID sessionId) {
        EmbeddedChannel channel = new EmbeddedChannel(new V2DeviceConnectionTracker(registry));
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT, deviceId, sessionId));
        channel.pipeline().fireUserEventTriggered(V2ConnectionPhaseEvent.AUTHENTICATED);
        return channel;
    }

    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT, ACTOR, SESSION));
    }

    private static Envelope command(MessageType type, ByteString payload) {
        return Envelope.newBuilder().setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND).setMessageType(type.getNumber())
                .setRequestId("request-1").setSessionId(SESSION.toString())
                .setPayload(payload).build();
    }

    private static ProtocolError error(EmbeddedChannel channel) {
        try {
            return ProtocolError.parseFrom(((Envelope) channel.readOutbound()).getPayload());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static class StubPort implements DeviceManagementPort {
        @Override public DeviceDirectoryResult listActive(
                com.fallingnight.chat.application.identity.AuthenticatedDeviceActor actor) {
            return DeviceDirectoryResult.Rejected.INSTANCE;
        }
        @Override public DeviceRevocationResult revokeOther(
                com.fallingnight.chat.application.identity.AuthenticatedDeviceActor actor,
                UUID targetDeviceId) {
            return DeviceRevocationResult.Rejected.INSTANCE;
        }
    }

    private static final class HoldingExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.addLast(command); }
    }
}
