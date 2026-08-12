package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRemovalResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class V1JsonFriendRemovalCodecTest {
    private final V1JsonFriendRemovalCodec codec = new V1JsonFriendRemovalCodec(Clock.fixed(
            Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void decodesExactUsernameAndKeepsLegacyResponses() {
        var decoded = codec.decode(bytes(
                "{\"type\":\"FRIEND_REMOVE_REQ\",\"data\":{\"username\":\"peer\"}}"));
        assertEquals(V1JsonFriendRemovalCodec.RequestKind.REMOVE, decoded.kind());
        assertEquals("peer", decoded.username());
        assertEquals(V1JsonFriendRemovalCodec.RequestKind.MALFORMED_REMOVE,
                codec.decode(bytes("{\"type\":\"FRIEND_REMOVE_REQ\",\"data\":{"
                        + "\"username\":1}}")).kind());

        String success = text(codec.encodeResponse(new LegacyV1FriendRemovalResult.Removed(
                false, UUID.randomUUID(), "peer")));
        assertTrue(success.contains("\"success\":true"));
        assertTrue(success.contains("\"username\":\"peer\""));
        String self = text(codec.encodeResponse(
                LegacyV1FriendRemovalResult.Rejected.SELF_REMOVAL));
        assertTrue(self.contains("\u4e0d\u80fd\u5220\u9664\u81ea\u5df1"));
        String generic = text(codec.encodeResponse(
                LegacyV1FriendRemovalResult.Rejected.NOT_FRIENDS));
        assertTrue(generic.contains("\u5220\u9664\u597d\u53cb\u5931\u8d25"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
