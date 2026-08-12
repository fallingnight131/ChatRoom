package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomSummary;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

final class V1JsonRoomDirectoryCodecTest {
    private final V1JsonRoomDirectoryCodec codec = new V1JsonRoomDirectoryCodec(
            Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void classifiesOnlyStrictRoomListRequests() {
        assertEquals(V1JsonRoomDirectoryCodec.RequestKind.ROOM_LIST,
                classify("{\"type\":\"ROOM_LIST_REQ\",\"id\":\"one\",\"data\":{}}"));
        assertEquals(V1JsonRoomDirectoryCodec.RequestKind.MALFORMED_ROOM_LIST,
                classify("{\"type\":\"ROOM_LIST_REQ\",\"data\":[]}"));
        assertEquals(V1JsonRoomDirectoryCodec.RequestKind.MALFORMED_ROOM_LIST,
                classify("{\"type\":\"ROOM_LIST_REQ\",\"type\":\"ROOM_LIST_REQ\",\"data\":{}}"));
        assertEquals(V1JsonRoomDirectoryCodec.RequestKind.OTHER,
                classify("{\"type\":\"FRIEND_LIST_REQ\",\"data\":{}}"));
    }

    @Test
    void encodesTheExistingBoundedRoomShapeWithoutExposingCanonicalIds() {
        String response = new String(codec.encode(List.of(
                new LegacyV1RoomSummary(7, "工程群", 3, true),
                new LegacyV1RoomSummary(9, "General", (long) Integer.MAX_VALUE + 9, false))),
                StandardCharsets.UTF_8);
        assertTrue(response.contains("\"type\":\"ROOM_LIST_RSP\""));
        assertTrue(response.contains("\"timestamp\":1786579200000"));
        assertTrue(response.contains("\"roomId\":7"));
        assertTrue(response.contains("\"roomName\":\"工程群\""));
        assertTrue(response.contains("\"creatorId\":0"));
        assertTrue(response.contains("\"unread\":2147483647"));
        assertTrue(response.contains("\"isAdmin\":true"));
        assertTrue(!response.contains("accountId") && !response.contains("conversationId"));
    }

    private V1JsonRoomDirectoryCodec.RequestKind classify(String value) {
        return codec.classify(value.getBytes(StandardCharsets.UTF_8));
    }
}
