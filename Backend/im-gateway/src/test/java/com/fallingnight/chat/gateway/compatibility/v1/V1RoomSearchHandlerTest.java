package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

final class V1RoomSearchHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final LegacyV1AuthenticatedIdentity IDENTITY =
            new LegacyV1AuthenticatedIdentity(42, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), NOW.plusSeconds(60), "owner", "Owner", false);

    @Test void codecUsesExactShapeAndNeverEmitsCanonicalIdentity() {
        V1JsonRoomSearchCodec codec = codec();
        var decoded = codec.decode(bytes(
                "{\"type\":\"ROOM_SEARCH_REQ\",\"data\":{\"keyword\":\"room\"}}"));
        assertEquals(V1JsonRoomSearchCodec.RequestKind.SEARCH, decoded.kind());
        assertEquals("room", decoded.keyword());
        assertEquals(V1JsonRoomSearchCodec.RequestKind.MALFORMED_SEARCH, codec.decode(bytes(
                "{\"type\":\"ROOM_SEARCH_REQ\",\"data\":{\"keyword\":1}}" )).kind());
        String response = new String(codec.encode(new LegacyV1RoomSearchResult.Found(List.of(
                new LegacyV1RoomSearchRoom(7, "Room", 42, 3)))), StandardCharsets.UTF_8);
        assertTrue(response.contains("\"type\":\"ROOM_SEARCH_RSP\""));
        assertTrue(response.contains("\"roomId\":7"));
        assertTrue(response.contains("\"creatorId\":42"));
        assertTrue(response.contains("\"memberCount\":3"));
        assertFalse(response.contains("conversationId"));
    }

    @Test void bindsActorReturnsResultAndBusinessRejectionWithoutClosing() {
        EmbeddedChannel channel = channel((actor, keyword) -> {
            assertEquals(IDENTITY.accountId(), actor);
            return keyword.isBlank() ? LegacyV1RoomSearchResult.Rejected.INSTANCE
                    : new LegacyV1RoomSearchResult.Found(List.of(
                            new LegacyV1RoomSearchRoom(7, "Room", 42, 3)));
        }, Runnable::run);
        try {
            authenticate(channel); channel.writeInbound(request("room")); channel.runPendingTasks();
            TextWebSocketFrame found = channel.readOutbound();
            try { assertTrue(found.text().contains("\"roomId\":7")); }
            finally { found.release(); }
            channel.writeInbound(request(" ")); channel.runPendingTasks();
            TextWebSocketFrame rejected = channel.readOutbound();
            try { assertTrue(rejected.text().contains("\"success\":false")); }
            finally { rejected.release(); }
            assertTrue(channel.isActive());
            TextWebSocketFrame other = new TextWebSocketFrame(
                    "{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}");
            assertTrue(channel.writeInbound(other));
            ((TextWebSocketFrame) channel.readInbound()).release();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void malformedDependencyFailureAndSaturationClose() {
        EmbeddedChannel malformed = channel((actor, keyword) ->
                new LegacyV1RoomSearchResult.Found(List.of()), Runnable::run);
        try {
            authenticate(malformed); malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"ROOM_SEARCH_REQ\",\"data\":{\"keyword\":1}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }
        EmbeddedChannel failed = channel((actor, keyword) -> {
            throw new IllegalStateException("private");
        }, Runnable::run);
        try {
            authenticate(failed); failed.writeInbound(request("room")); failed.runPendingTasks();
            ((CloseWebSocketFrame) failed.readOutbound()).release(); assertFalse(failed.isActive());
        } finally { failed.finishAndReleaseAll(); }
        EmbeddedChannel saturated = channel((actor, keyword) ->
                new LegacyV1RoomSearchResult.Found(List.of()), command -> {
                    throw new RejectedExecutionException("full");
                });
        try {
            authenticate(saturated); saturated.writeInbound(request("room"));
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(LegacyV1RoomSearchUseCase useCase,
            java.util.concurrent.Executor executor) {
        return new EmbeddedChannel(new V1RoomSearchHandler(useCase, codec(), executor,
                V1RoomSearchEventSink.noop()));
    }
    private static V1JsonRoomSearchCodec codec() {
        return new V1JsonRoomSearchCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(IDENTITY);
    }
    private static TextWebSocketFrame request(String keyword) {
        return new TextWebSocketFrame("{\"type\":\"ROOM_SEARCH_REQ\",\"data\":{\"keyword\":\""
                + keyword + "\"}}");
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
