package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectMessageResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class V1JsonDirectMessageCodecTest {
    private final V1JsonDirectMessageCodec codec = new V1JsonDirectMessageCodec(Clock.fixed(
            Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void decodesExplicitAndEnvelopeFallbackIdsAndEncodesMappedAcceptance() {
        var explicit = codec.decode(bytes("{\"type\":\"FRIEND_CHAT_MSG\",\"id\":\"env\","
                + "\"data\":{\"friendUsername\":\"peer\",\"content\":\"hello\","
                + "\"contentType\":\"text\",\"clientMessageId\":\"client\"}}"));
        assertEquals(V1JsonDirectMessageCodec.RequestKind.SUBMIT, explicit.kind());
        assertEquals("client", explicit.clientMessageId());
        var fallback = codec.decode(bytes("{\"type\":\"FRIEND_CHAT_MSG\",\"id\":\"env\","
                + "\"data\":{\"friendUsername\":\"peer\",\"content\":\"hello\"}}"));
        assertEquals("env", fallback.clientMessageId());
        assertEquals("text", fallback.contentType());

        var accepted = new LegacyV1DirectMessageResult.Accepted(false, 9, 101, 3,
                Instant.parse("2026-08-13T12:00:00Z"), UUID.randomUUID(), "peer");
        String response = text(codec.encodeResponse(accepted, "peer", "client"));
        assertTrue(response.contains("\"friendshipId\":9"));
        assertTrue(response.contains("\"id\":101"));
        assertTrue(response.contains("\"duplicate\":false"));
        String notification = text(codec.encodeNotification(
                accepted, "sender", "Sender", "client", "hello", "text"));
        assertTrue(notification.contains("\"sender\":\"sender\""));
        assertTrue(notification.contains("\"content\":\"hello\""));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
}
