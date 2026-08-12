package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestCreationResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

final class V1JsonFriendRequestCreationCodecTest {
    private final V1JsonFriendRequestCreationCodec codec =
            new V1JsonFriendRequestCreationCodec(Clock.fixed(
                    Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void decodesExactUsernameAndKeepsLegacyErrors() {
        var decoded = codec.decode(bytes(
                "{\"type\":\"FRIEND_REQUEST_REQ\",\"data\":{\"username\":\"peer\"}}"));
        assertEquals(V1JsonFriendRequestCreationCodec.RequestKind.CREATE, decoded.kind());
        assertEquals("peer", decoded.username());
        assertEquals(V1JsonFriendRequestCreationCodec.RequestKind.MALFORMED_CREATE,
                codec.decode(bytes("{\"type\":\"FRIEND_REQUEST_REQ\",\"data\":{"
                        + "\"username\":1}}")).kind());
        String reverse = new String(codec.encodeResponse(
                LegacyV1FriendRequestCreationResult.Rejected.REVERSE_PENDING),
                StandardCharsets.UTF_8);
        assertTrue(reverse.contains("\u5bf9\u65b9\u5df2\u5411\u4f60\u53d1\u9001\u4e86\u597d\u53cb\u7533\u8bf7"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
