package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.protocol.v2.ClientHello;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.ClientPlatform;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.ServerHello;
import com.google.protobuf.ByteString;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V2HandshakeHandlerTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void negotiatesV2AndReturnsOnlyServerAuthorityFields() throws Exception {
        EmbeddedChannel channel = channel();
        try {
            Envelope request = clientHelloEnvelope(validHello());
            assertFalse(channel.writeInbound(new BinaryWebSocketFrame(
                    Unpooled.wrappedBuffer(request.toByteArray()))));
            Envelope response = readEnvelope(channel);
            assertEquals(MessageKind.MESSAGE_KIND_RESPONSE, response.getKind());
            assertEquals(MessageType.MESSAGE_TYPE_SERVER_HELLO_VALUE, response.getMessageType());
            assertEquals("hello-1", response.getRequestId());
            assertEquals(NOW, response.getSentAtEpochMs());
            ServerHello hello = ServerHello.parseFrom(response.getPayload());
            assertEquals(2, hello.getSelectedProtocolVersion());
            assertEquals("connection-1", hello.getConnectionId());
            assertEquals(NOW, hello.getServerTimeEpochMs());
            assertEquals(V2EnvelopeDecoder.MAX_WIRE_BYTES, hello.getMaximumFrameBytes());
            assertEquals(0, hello.getEnabledCapabilitiesCount());
            assertTrue(channel.isActive());
            assertEquals(
                    "device-1",
                    channel.attr(V2ConnectionAttributes.NEGOTIATED_CLIENT)
                            .get().clientDeviceId());

            Envelope next = clientHelloEnvelope(validHello()).toBuilder()
                    .setMessageType(99)
                    .build();
            assertTrue(channel.writeInbound(next));
            assertEquals(next, channel.readInbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void enablesOnlyTheExplicitlyRequestedKnownCapability() throws Exception {
        EmbeddedChannel channel = channel();
        try {
            ClientHello capable = validHello().toBuilder()
                    .addCapabilities(ClientCapability.CLIENT_CAPABILITY_MESSAGE_REACTIONS)
                    .build();
            channel.writeInbound(clientHelloEnvelope(capable));
            ServerHello response = ServerHello.parseFrom(readEnvelope(channel).getPayload());
            assertEquals(List.of(ClientCapability.CLIENT_CAPABILITY_MESSAGE_REACTIONS),
                    response.getEnabledCapabilitiesList());
            assertEquals(Set.of(ClientCapability.CLIENT_CAPABILITY_MESSAGE_REACTIONS),
                    channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void safelyRejectsWrongFirstFrameAndCloses() throws Exception {
        Envelope wrong = clientHelloEnvelope(validHello()).toBuilder()
                .setMessageType(99)
                .build();
        assertProtocolError(
                wrong,
                ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                "ClientHello must be the first application frame");
    }

    @Test
    void safelyRejectsMalformedOversizedAndUnsupportedHello() throws Exception {
        assertProtocolError(
                clientHelloEnvelopeBytes(ByteString.copyFrom(new byte[] {(byte) 0x80})),
                ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                "invalid ClientHello payload");
        assertProtocolError(
                clientHelloEnvelopeBytes(ByteString.copyFrom(
                        new byte[V2HandshakeHandler.MAX_CLIENT_HELLO_BYTES + 1])),
                ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                "invalid ClientHello payload");
        ClientHello future = validHello().toBuilder()
                .setMinimumProtocolVersion(3)
                .setMaximumProtocolVersion(4)
                .build();
        assertProtocolError(
                clientHelloEnvelope(future),
                ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_VERSION,
                "protocol version is not supported");
    }

    @Test
    void rejectsClientHelloPlatformThatDoesNotMatchUpgradeEndpoint() throws Exception {
        EmbeddedChannel channel = channel();
        channel.attr(V2ConnectionAttributes.EXPECTED_CLIENT_PLATFORM).set(
                com.fallingnight.chat.application.identity.ClientPlatform.WINDOWS);
        try {
            channel.writeInbound(clientHelloEnvelope(validHello()));
            assertError(
                    readEnvelope(channel),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    "client platform does not match endpoint");
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsRepeatedHandshakeAfterNegotiation() throws Exception {
        EmbeddedChannel channel = channel();
        try {
            channel.writeInbound(clientHelloEnvelope(validHello()));
            readEnvelope(channel);
            channel.writeInbound(clientHelloEnvelope(validHello()));
            assertError(
                    readEnvelope(channel),
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_STATE,
                    "handshake already completed");
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void assertProtocolError(
            Envelope input, ProtocolErrorCode code, String safeMessage) throws Exception {
        EmbeddedChannel channel = channel();
        try {
            channel.writeInbound(input);
            assertError(readEnvelope(channel), code, safeMessage);
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void assertError(
            Envelope envelope, ProtocolErrorCode code, String safeMessage) throws Exception {
        assertEquals(MessageKind.MESSAGE_KIND_ERROR, envelope.getKind());
        assertEquals(MessageType.MESSAGE_TYPE_PROTOCOL_ERROR_VALUE, envelope.getMessageType());
        ProtocolError error = ProtocolError.parseFrom(envelope.getPayload());
        assertEquals(code, error.getCode());
        assertEquals(safeMessage, error.getSafeMessage());
        assertFalse(error.getRetryable());
    }

    private static EmbeddedChannel channel() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        EmbeddedChannel channel = new EmbeddedChannel();
        V2FramePipeline.install(channel.pipeline());
        channel.pipeline().addLast(
                "v2-handshake", new V2HandshakeHandler(clock, () -> "connection-1"));
        return channel;
    }

    private static Envelope readEnvelope(EmbeddedChannel channel) throws Exception {
        BinaryWebSocketFrame frame = assertInstanceOf(
                BinaryWebSocketFrame.class, channel.readOutbound());
        try {
            return Envelope.parseFrom(frame.content().nioBuffer());
        } finally {
            frame.release();
        }
    }

    private static Envelope clientHelloEnvelope(ClientHello hello) {
        return clientHelloEnvelopeBytes(hello.toByteString());
    }

    private static Envelope clientHelloEnvelopeBytes(ByteString payload) {
        return Envelope.newBuilder()
                .setProtocolVersion(2)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .setMessageType(MessageType.MESSAGE_TYPE_CLIENT_HELLO_VALUE)
                .setRequestId("hello-1")
                .setSentAtEpochMs(NOW)
                .setPayload(payload)
                .build();
    }

    private static ClientHello validHello() {
        return ClientHello.newBuilder()
                .setMinimumProtocolVersion(2)
                .setMaximumProtocolVersion(2)
                .setPlatform(ClientPlatform.CLIENT_PLATFORM_WEB)
                .setAppVersion("0.1.0")
                .setClientDeviceId("device-1")
                .build();
    }
}
