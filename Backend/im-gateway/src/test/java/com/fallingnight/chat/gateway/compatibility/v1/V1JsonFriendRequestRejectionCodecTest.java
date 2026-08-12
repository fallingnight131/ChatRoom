package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

final class V1JsonFriendRequestRejectionCodecTest {
    private final V1JsonFriendRequestRejectionCodec codec =
            new V1JsonFriendRequestRejectionCodec(Clock.fixed(
                    Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void decodesOnlyOnePositiveBoundedRequestId() {
        var decoded = codec.decode(bytes(
                "{\"type\":\"FRIEND_REJECT_REQ\",\"data\":{\"requestId\":70}}"));
        assertEquals(V1JsonFriendRequestRejectionCodec.RequestKind.REJECT, decoded.kind());
        assertEquals(70, decoded.requestId());

        assertMalformed("{\"type\":\"FRIEND_REJECT_REQ\",\"data\":{}}");
        assertMalformed("{\"type\":\"FRIEND_REJECT_REQ\",\"data\":{\"requestId\":0}}");
        assertMalformed("{\"type\":\"FRIEND_REJECT_REQ\",\"data\":{\"requestId\":70,\"x\":1}}");
        assertMalformed("{\"type\":\"FRIEND_REJECT_REQ\",\"data\":{\"requestId\":70,\"requestId\":70}}");
        assertEquals(V1JsonFriendRequestRejectionCodec.RequestKind.OTHER,
                codec.decode(bytes("{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}")).kind());
    }

    @Test
    void emitsExactCompatibleSuccessAndGenericFailureShapes() {
        String success = new String(codec.encode(true), StandardCharsets.UTF_8);
        assertTrue(success.contains("\"type\":\"FRIEND_REJECT_RSP\""));
        assertTrue(success.contains("\"success\":true"));
        String rejected = new String(codec.encode(false), StandardCharsets.UTF_8);
        assertTrue(rejected.contains("\"success\":false"));
        assertTrue(rejected.contains("\"error\":\"\u5904\u7406\u597d\u53cb\u8bf7\u6c42\u5931\u8d25\""));
    }

    private void assertMalformed(String json) {
        assertEquals(V1JsonFriendRequestRejectionCodec.RequestKind.MALFORMED_REJECT,
                codec.decode(bytes(json)).kind());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
