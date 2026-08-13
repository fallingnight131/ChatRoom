package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.identity.ClientDescriptor;
import com.fallingnight.chat.protocol.v2.ClientHello;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.ClientHelloPolicy;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.ServerHello;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Enforces ClientHello as the first V2 application frame on each connection. */
public final class V2HandshakeHandler extends SimpleChannelInboundHandler<Envelope> {
    public static final int MAX_CLIENT_HELLO_BYTES = 512;

    private final Clock clock;
    private final String connectionId;
    private boolean negotiated;

    public V2HandshakeHandler() {
        this(Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    V2HandshakeHandler(Clock clock, Supplier<String> connectionIdSupplier) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(connectionIdSupplier, "connectionIdSupplier");
        connectionId = requireConnectionId(connectionIdSupplier.get());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, Envelope envelope) {
        if (negotiated) {
            if (envelope.getMessageType() == MessageType.MESSAGE_TYPE_CLIENT_HELLO_VALUE) {
                failAndClose(
                        context,
                        envelope.getRequestId(),
                        ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                        "handshake already completed",
                        false);
                return;
            }
            context.fireChannelRead(envelope);
            return;
        }

        if (envelope.getMessageType() != MessageType.MESSAGE_TYPE_CLIENT_HELLO_VALUE
                || envelope.getKind() != MessageKind.MESSAGE_KIND_COMMAND) {
            failAndClose(
                    context,
                    envelope.getRequestId(),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "ClientHello must be the first application frame",
                    false);
            return;
        }
        if (envelope.getPayload().size() > MAX_CLIENT_HELLO_BYTES) {
            failAndClose(
                    context,
                    envelope.getRequestId(),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    "invalid ClientHello payload",
                    false);
            return;
        }

        final ClientHello hello;
        try {
            hello = ClientHello.parseFrom(envelope.getPayload());
            ClientHelloPolicy.requireValid(hello);
        } catch (InvalidProtocolBufferException | IllegalArgumentException exception) {
            failAndClose(
                    context,
                    envelope.getRequestId(),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    "invalid ClientHello payload",
                    false);
            return;
        }
        if (!ClientHelloPolicy.supportsCurrentVersion(hello)) {
            failAndClose(
                    context,
                    envelope.getRequestId(),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_VERSION,
                    "protocol version is not supported",
                    false);
            return;
        }
        com.fallingnight.chat.application.identity.ClientPlatform platform =
                toApplicationPlatform(hello.getPlatform());
        com.fallingnight.chat.application.identity.ClientPlatform expectedPlatform =
                context.channel()
                        .attr(V2ConnectionAttributes.EXPECTED_CLIENT_PLATFORM)
                        .get();
        if (expectedPlatform != null && expectedPlatform != platform) {
            failAndClose(
                    context,
                    envelope.getRequestId(),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    "client platform does not match endpoint",
                    false);
            return;
        }

        negotiated = true;
        context.channel().attr(V2ConnectionAttributes.NEGOTIATED_CLIENT).set(
                new ClientDescriptor(
                        hello.getClientDeviceId(),
                        platform,
                        hello.getAppVersion()));
        List<ClientCapability> enabledCapabilityList = hello.getCapabilitiesList().stream()
                .filter(capability -> capability
                                == ClientCapability.CLIENT_CAPABILITY_MESSAGE_REACTIONS
                        || capability == ClientCapability.CLIENT_CAPABILITY_MESSAGE_PINS)
                .toList();
        Set<ClientCapability> enabledCapabilities = Set.copyOf(enabledCapabilityList);
        context.channel().attr(V2ConnectionAttributes.ENABLED_CAPABILITIES)
                .set(enabledCapabilities);
        long now = clock.millis();
        ServerHello response = ServerHello.newBuilder()
                .setSelectedProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setConnectionId(connectionId)
                .setServerTimeEpochMs(now)
                .setMaximumFrameBytes(V2EnvelopeDecoder.MAX_WIRE_BYTES)
                .addAllEnabledCapabilities(enabledCapabilityList)
                .build();
        context.writeAndFlush(Envelope.newBuilder()
                .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_RESPONSE)
                .setMessageType(MessageType.MESSAGE_TYPE_SERVER_HELLO_VALUE)
                .setRequestId(envelope.getRequestId())
                .setSentAtEpochMs(now)
                .setPayload(response.toByteString())
                .build());
        context.fireUserEventTriggered(V2ConnectionPhaseEvent.NEGOTIATED);
    }

    private static com.fallingnight.chat.application.identity.ClientPlatform toApplicationPlatform(
            com.fallingnight.chat.protocol.v2.ClientPlatform platform) {
        return switch (platform) {
            case CLIENT_PLATFORM_WEB ->
                    com.fallingnight.chat.application.identity.ClientPlatform.WEB;
            case CLIENT_PLATFORM_WINDOWS ->
                    com.fallingnight.chat.application.identity.ClientPlatform.WINDOWS;
            case CLIENT_PLATFORM_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("unsupported negotiated platform");
        };
    }

    private void failAndClose(
            ChannelHandlerContext context,
            String requestId,
            ProtocolErrorCode code,
            String safeMessage,
            boolean retryable) {
        long now = clock.millis();
        ProtocolError error = ProtocolError.newBuilder()
                .setCode(code)
                .setSafeMessage(safeMessage)
                .setRetryable(retryable)
                .build();
        Envelope response = Envelope.newBuilder()
                .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_ERROR)
                .setMessageType(MessageType.MESSAGE_TYPE_PROTOCOL_ERROR_VALUE)
                .setRequestId(requestId)
                .setSentAtEpochMs(now)
                .setPayload(error.toByteString())
                .build();
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static String requireConnectionId(String value) {
        if (value == null || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > EnvelopePolicy.MAX_IDENTIFIER_BYTES) {
            throw new IllegalArgumentException("connectionId must contain 1..128 UTF-8 bytes");
        }
        return value;
    }
}
