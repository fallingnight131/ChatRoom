package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.identity.AuthenticatedDeviceActor;
import com.fallingnight.chat.application.identity.DeviceDirectoryResult;
import com.fallingnight.chat.application.identity.DeviceManagementService;
import com.fallingnight.chat.application.identity.DeviceRevocationResult;
import com.fallingnight.chat.application.identity.ManagedDevice;
import com.fallingnight.chat.protocol.v2.DeviceDirectory;
import com.fallingnight.chat.protocol.v2.DeviceManagementPayloadPolicy;
import com.fallingnight.chat.protocol.v2.DeviceRevoked;
import com.fallingnight.chat.protocol.v2.DeviceSummary;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.ListDevices;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.MessageTypeRegistry;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.RevokeDevice;
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

/** Serialized, off-event-loop V2 adapter for user-managed device security commands. */
public final class V2DeviceManagementHandler extends SimpleChannelInboundHandler<Envelope> {
    static final int MAX_PENDING_COMMANDS = 8;

    private final DeviceManagementService service;
    private final Executor executor;
    private final DeviceConnectionRegistry connections;
    private final DeviceManagementEventSink events;
    private final Clock clock;
    private final ArrayDeque<Envelope> pending = new ArrayDeque<>();
    private boolean inFlight;

    public V2DeviceManagementHandler(DeviceManagementService service, Executor executor,
            DeviceConnectionRegistry connections, DeviceManagementEventSink events) {
        this(service, executor, connections, events, Clock.systemUTC());
    }

    V2DeviceManagementHandler(DeviceManagementService service, Executor executor,
            DeviceConnectionRegistry connections, DeviceManagementEventSink events, Clock clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean acceptInboundMessage(Object message) {
        if (!(message instanceof Envelope envelope)) return false;
        MessageType type = MessageTypeRegistry.find(envelope.getMessageType()).orElse(null);
        return type == MessageType.MESSAGE_TYPE_LIST_DEVICES
                || type == MessageType.MESSAGE_TYPE_REVOKE_DEVICE;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, Envelope envelope) {
        if (context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get() == null) {
            writeError(context, envelope, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "authentication is required", false);
            return;
        }
        if (envelope.getKind() != MessageKind.MESSAGE_KIND_COMMAND) {
            invalid(context, envelope);
            return;
        }
        if (pending.size() >= MAX_PENDING_COMMANDS) {
            events.saturated();
            writeError(context, envelope, ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED,
                    "too many pending device commands", true);
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
        if (inFlight || pending.isEmpty() || !context.channel().isActive()) return;
        Envelope request = pending.removeFirst();
        final Work work;
        try {
            work = parse(context, request);
        } catch (InvalidProtocolBufferException | IllegalArgumentException exception) {
            invalid(context, request);
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
                    "device service is busy", true);
            dispatchNext(context);
        }
    }

    private Work parse(ChannelHandlerContext context, Envelope request)
            throws InvalidProtocolBufferException {
        AuthenticatedConnection connection = Objects.requireNonNull(
                context.channel().attr(V2ConnectionAttributes.AUTHENTICATED).get());
        var actor = new AuthenticatedDeviceActor(connection.accountId(), connection.deviceId(),
                connection.sessionId());
        MessageType type = MessageTypeRegistry.find(request.getMessageType()).orElseThrow();
        if (type == MessageType.MESSAGE_TYPE_LIST_DEVICES) {
            ListDevices payload = ListDevices.parseFrom(request.getPayload());
            DeviceManagementPayloadPolicy.requireValid(payload);
            return new ListWork(actor);
        }
        RevokeDevice payload = RevokeDevice.parseFrom(request.getPayload());
        DeviceManagementPayloadPolicy.requireValid(payload);
        return new RevokeWork(actor, UUID.fromString(payload.getTargetDeviceId()));
    }

    private void execute(ChannelHandlerContext context, Envelope request, Work work) {
        Envelope response;
        try {
            if (work instanceof ListWork list) {
                response = listResponse(request, service.listActive(list.actor()));
            } else {
                RevokeWork revoke = (RevokeWork) work;
                response = revokeResponse(request,
                        service.revokeOther(revoke.actor(), revoke.targetDeviceId()));
            }
        } catch (RuntimeException exception) {
            events.failed();
            response = error(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR,
                    "device service is temporarily unavailable", true);
        }
        scheduleCompletion(context, response);
    }

    private Envelope listResponse(Envelope request, DeviceDirectoryResult result) {
        if (result == DeviceDirectoryResult.Rejected.INSTANCE) return denied(request);
        DeviceDirectory.Builder payload = DeviceDirectory.newBuilder();
        for (ManagedDevice device : ((DeviceDirectoryResult.Available) result).devices()) {
            payload.addDevices(DeviceSummary.newBuilder().setDeviceId(device.deviceId().toString())
                    .setPlatform(switch (device.platform()) {
                        case WEB -> com.fallingnight.chat.protocol.v2.ClientPlatform.CLIENT_PLATFORM_WEB;
                        case WINDOWS -> com.fallingnight.chat.protocol.v2.ClientPlatform.CLIENT_PLATFORM_WINDOWS;
                    }).setCreatedAtEpochMs(device.createdAt().toEpochMilli())
                    .setLastSeenAtEpochMs(device.lastSeenAt().toEpochMilli())
                    .setCurrent(device.current()));
        }
        DeviceDirectory built = payload.build();
        DeviceManagementPayloadPolicy.requireValid(built);
        events.listed();
        return response(request, MessageType.MESSAGE_TYPE_DEVICE_DIRECTORY, built.toByteString());
    }

    private Envelope revokeResponse(Envelope request, DeviceRevocationResult result) {
        if (result == DeviceRevocationResult.Rejected.INSTANCE) return denied(request);
        DeviceRevocationResult.Revoked revoked = (DeviceRevocationResult.Revoked) result;
        DeviceRevoked payload = DeviceRevoked.newBuilder()
                .setTargetDeviceId(revoked.targetDeviceId().toString())
                .setRevokedAtEpochMs(revoked.revokedAt().toEpochMilli())
                .setRevokedSessions(revoked.revokedSessions()).setChanged(revoked.changed()).build();
        DeviceManagementPayloadPolicy.requireValid(payload);
        events.revoked(revoked.changed());
        int closed = connections.close(revoked.targetDeviceId());
        if (closed > 0) events.disconnected(closed);
        return response(request, MessageType.MESSAGE_TYPE_DEVICE_REVOKED, payload.toByteString());
    }

    private Envelope denied(Envelope request) {
        events.denied();
        return error(request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                "device operation is not available", false);
    }

    private void invalid(ChannelHandlerContext context, Envelope request) {
        events.invalid();
        writeError(context, request, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                "invalid device payload", false);
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

    private Envelope response(Envelope request, MessageType type, ByteString payload) {
        return envelope(request, MessageKind.MESSAGE_KIND_RESPONSE, type, payload);
    }

    private Envelope envelope(Envelope request, MessageKind kind, MessageType type,
            ByteString payload) {
        return Envelope.newBuilder().setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(kind).setMessageType(type.getNumber()).setRequestId(request.getRequestId())
                .setSessionId(request.getSessionId()).setClientMessageId(request.getClientMessageId())
                .setSentAtEpochMs(clock.millis()).setPayload(payload).build();
    }

    private sealed interface Work { }
    private record ListWork(AuthenticatedDeviceActor actor) implements Work { }
    private record RevokeWork(AuthenticatedDeviceActor actor, UUID targetDeviceId) implements Work { }
}
