package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1UserSearchResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1UserSearchUser;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

final class V1JsonUserSearchCodecTest {
    private final V1JsonUserSearchCodec codec = new V1JsonUserSearchCodec(Clock.fixed(
            Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void decodesOnlyTheExactKeywordShape() {
        var request = codec.decode(bytes(
                "{\"type\":\"USER_SEARCH_REQ\",\"data\":{\"keyword\":\"peer\"}}"));
        assertEquals(V1JsonUserSearchCodec.RequestKind.SEARCH, request.kind());
        assertEquals("peer", request.keyword());
        assertMalformed("{\"type\":\"USER_SEARCH_REQ\",\"data\":{\"keyword\":1}}");
        assertMalformed("{\"type\":\"USER_SEARCH_REQ\",\"data\":{"
                + "\"keyword\":\"a\",\"keyword\":\"b\"}}");
        assertMalformed("{\"type\":\"USER_SEARCH_REQ\",\"data\":{"
                + "\"keyword\":\"a\",\"extra\":true}}");
    }

    @Test
    void emitsExactLegacyFieldsWithoutCanonicalIdentity() {
        String response = string(codec.encode(new LegacyV1UserSearchResult.Found(List.of(
                new LegacyV1UserSearchUser(44, "peer", "Peer", true)))));
        assertTrue(response.contains("\"type\":\"USER_SEARCH_RSP\""));
        assertTrue(response.contains("\"success\":true"));
        assertTrue(response.contains("\"userId\":44"));
        assertTrue(response.contains("\"online\":true"));
        String rejected = string(codec.encode(LegacyV1UserSearchResult.Rejected.INSTANCE));
        assertTrue(rejected.contains("\"success\":false"));
        assertTrue(rejected.contains("\"error\":\"\u641c\u7d22\u5173\u952e\u8bcd\u4e0d\u80fd\u4e3a\u7a7a\""));
    }

    private void assertMalformed(String json) {
        assertEquals(V1JsonUserSearchCodec.RequestKind.MALFORMED_SEARCH,
                codec.decode(bytes(json)).kind());
    }
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
    private static String string(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
