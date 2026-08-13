package com.fallingnight.chat.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ControlProtocolTest {
    private static final String CLIENT_HELLO_GOLDEN =
            "0802100218012205302e312e302a086465766963652d31";

    @Test
    void clientHelloHasStableWireBytesAndRegistryKind() throws Exception {
        ClientHello hello = validHello();
        ClientHelloPolicy.requireValid(hello);
        assertTrue(ClientHelloPolicy.supportsCurrentVersion(hello));
        assertEquals(CLIENT_HELLO_GOLDEN, HexFormat.of().formatHex(hello.toByteArray()));
        assertEquals(hello, ClientHello.parseFrom(HexFormat.of().parseHex(CLIENT_HELLO_GOLDEN)));

        Envelope envelope = Envelope.newBuilder()
                .setProtocolVersion(2)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .setMessageType(MessageType.MESSAGE_TYPE_CLIENT_HELLO_VALUE)
                .setRequestId("request-1")
                .setSentAtEpochMs(1_700_000_000_000L)
                .setPayload(hello.toByteString())
                .build();
        MessageTypeRegistry.requireRegisteredKind(envelope);
    }

    @Test
    void rejectsUnknownOrKindMismatchedRegistryEntries() {
        Envelope unknown = Envelope.newBuilder()
                .setMessageType(999)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> MessageTypeRegistry.requireRegisteredKind(unknown));

        Envelope wrongKind = unknown.toBuilder()
                .setMessageType(MessageType.MESSAGE_TYPE_SERVER_HELLO_VALUE)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> MessageTypeRegistry.requireRegisteredKind(wrongKind));
    }

    @Test
    void boundsUnauthenticatedHandshakeFieldsByUtf8Bytes() {
        ClientHello invalid = validHello().toBuilder()
                .setMinimumProtocolVersion(3)
                .setMaximumProtocolVersion(2)
                .setPlatform(ClientPlatform.CLIENT_PLATFORM_UNSPECIFIED)
                .setAppVersion("")
                .setClientDeviceId("聊".repeat(43))
                .build();
        var violations = ClientHelloPolicy.violations(invalid);
        assertEquals(4, violations.size());
        assertTrue(violations.stream().anyMatch(value -> value.startsWith("clientDeviceId")));
    }

    @Test
    void validatesExplicitCapabilitiesWithoutChangingLegacyGolden() {
        ClientHello capable = validHello().toBuilder()
                .addCapabilities(ClientCapability.CLIENT_CAPABILITY_MESSAGE_REACTIONS)
                .addCapabilities(ClientCapability.CLIENT_CAPABILITY_MESSAGE_PINS)
                .build();
        ClientHelloPolicy.requireValid(capable);
        assertThrows(IllegalArgumentException.class, () ->
                ClientHelloPolicy.requireValid(capable.toBuilder()
                        .addCapabilities(ClientCapability.CLIENT_CAPABILITY_MESSAGE_REACTIONS)
                        .build()));
        assertThrows(IllegalArgumentException.class, () ->
                ClientHelloPolicy.requireValid(validHello().toBuilder()
                        .addCapabilities(ClientCapability.CLIENT_CAPABILITY_UNSPECIFIED)
                        .build()));
    }

    @Test
    void separatesUnsupportedVersionFromStructurallyInvalidRanges() {
        ClientHello future = validHello().toBuilder()
                .setMinimumProtocolVersion(3)
                .setMaximumProtocolVersion(4)
                .build();
        ClientHelloPolicy.requireValid(future);
        assertFalse(ClientHelloPolicy.supportsCurrentVersion(future));
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
