package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

final class V1JsonFriendRequestAcceptanceCodecTest {
    private final V1JsonFriendRequestAcceptanceCodec codec =
            new V1JsonFriendRequestAcceptanceCodec(Clock.fixed(
                    Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void acceptsLegacyUsernameHintButDerivesOnlyTheRequestId() {
        var request = codec.decode(bytes("{\"type\":\"FRIEND_ACCEPT_REQ\",\"data\":{"
                + "\"requestId\":70,\"fromUsername\":\"untrusted\"}}"));
        assertEquals(V1JsonFriendRequestAcceptanceCodec.RequestKind.ACCEPT, request.kind());
        assertEquals(70, request.requestId());
        assertMalformed("{\"type\":\"FRIEND_ACCEPT_REQ\",\"data\":{\"requestId\":0}}");
        assertMalformed("{\"type\":\"FRIEND_ACCEPT_REQ\",\"data\":{"
                + "\"requestId\":70,\"fromUsername\":1}}");
        assertMalformed("{\"type\":\"FRIEND_ACCEPT_REQ\",\"data\":{"
                + "\"requestId\":70,\"requestId\":70}}");
    }

    @Test
    void emitsCompatibleResponseAndNotificationFields() {
        String failure = string(codec.encodeResponse(false));
        assertTrue(failure.contains("\"type\":\"FRIEND_ACCEPT_RSP\""));
        assertTrue(failure.contains("\"success\":false"));
        assertTrue(failure.contains("\"error\":\"\u5904\u7406\u597d\u53cb\u8bf7\u6c42\u5931\u8d25\""));
        String notification = string(codec.encodeNotification("owner", "Owner"));
        assertTrue(notification.contains("\"type\":\"FRIEND_ACCEPT_NOTIFY\""));
        assertTrue(notification.contains("\"acceptedBy\":\"owner\""));
        assertTrue(notification.contains("\"acceptedByDisplay\":\"Owner\""));
    }

    private void assertMalformed(String json) {
        assertEquals(V1JsonFriendRequestAcceptanceCodec.RequestKind.MALFORMED_ACCEPT,
                codec.decode(bytes(json)).kind());
    }
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
    private static String string(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
