package com.fallingnight.chat.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class WebPushProtocolTest {
    private static final String CREDENTIAL_GOLDEN_HEX =
            "0a2b61616161616161616161616161616161616161616161616161616161616161616161616161616161616161122b626262626262626262626262626262626262626262626262626262626262626262626262626262626262621880a0f1c2b134";

    @Test
    void locksCapabilityMessageTypesKindsAndCredentialWireShape() throws Exception {
        assertEquals(8,
                ClientCapability.CLIENT_CAPABILITY_WEB_PUSH_HTTP_CREDENTIAL_VALUE);
        assertEquals(136,
                MessageType.MESSAGE_TYPE_ISSUE_WEB_PUSH_HTTP_CREDENTIAL_VALUE);
        assertEquals(137,
                MessageType.MESSAGE_TYPE_WEB_PUSH_HTTP_CREDENTIAL_ISSUED_VALUE);
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND, MessageTypeRegistry.requiredKind(
                MessageType.MESSAGE_TYPE_ISSUE_WEB_PUSH_HTTP_CREDENTIAL));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE, MessageTypeRegistry.requiredKind(
                MessageType.MESSAGE_TYPE_WEB_PUSH_HTTP_CREDENTIAL_ISSUED));
        byte[] bearer = "a".repeat(43).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] csrf = "b".repeat(43).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var encoded = WebPushHttpCredentialIssued.newBuilder()
                .setBearerTokenAscii(com.google.protobuf.ByteString.copyFrom(bearer))
                .setCsrfTokenAscii(com.google.protobuf.ByteString.copyFrom(csrf))
                .setExpiresAtEpochMs(1_800_000_000_000L)
                .build().toByteArray();
        assertEquals(CREDENTIAL_GOLDEN_HEX, HexFormat.of().formatHex(encoded));
        var decoded = WebPushHttpCredentialIssued.parseFrom(encoded);
        assertArrayEquals(bearer, decoded.getBearerTokenAscii().toByteArray());
        assertArrayEquals(csrf, decoded.getCsrfTokenAscii().toByteArray());
        assertEquals(1_800_000_000_000L, decoded.getExpiresAtEpochMs());
    }
}
