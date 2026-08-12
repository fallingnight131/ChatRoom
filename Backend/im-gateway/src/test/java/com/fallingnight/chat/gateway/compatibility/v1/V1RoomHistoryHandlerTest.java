package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class V1RoomHistoryHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final LegacyV1AuthenticatedIdentity IDENTITY = new LegacyV1AuthenticatedIdentity(
            42, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            NOW.plusSeconds(60), "owner", "Owner", false);

    @Test void bindsActorAndEncodesMixedSequencePage() {
        AtomicReference<LegacyV1RoomHistoryQuery> captured = new AtomicReference<>();
        EmbeddedChannel channel = channel(query -> {
            captured.set(query);
            return new LegacyV1RoomHistoryResult.Page(7, true,
                    List.of(new LegacyV1RoomHistoryMessage(101, 1, 3L, 3, "client-1",
                            "owner", "Owner", "hello", "text", true, NOW)),
                    List.of(new LegacyV1RoomHistoryDeletion(900, 4, "Admin", "delete-1",
                            "selected", List.of(101L), List.of(), 0, 1, NOW)), 4, 4, false);
        }, Runnable::run);
        try {
            authenticate(channel); channel.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"HISTORY_REQ\",\"data\":{\"roomId\":7,\"count\":999,\"afterSequence\":0}}"));
            channel.runPendingTasks(); TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"HISTORY_RSP\""));
                assertTrue(response.text().contains("\"mutationSequence\":3"));
                assertTrue(response.text().contains("\"eventType\":\"messagesDeleted\""));
                assertTrue(response.text().contains("\"eventId\":900"));
                assertTrue(response.text().contains("\"nextSequence\":4"));
            } finally { response.release(); }
            assertEquals(IDENTITY.accountId(), captured.get().accountId());
            assertEquals(100, captured.get().limit());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void returnsRejectionAndClosesMalformedOrSaturated() {
        EmbeddedChannel rejected = channel(query ->
                LegacyV1RoomHistoryResult.Rejected.ROOM_ACCESS_DENIED, Runnable::run);
        try {
            authenticate(rejected); rejected.writeInbound(request()); rejected.runPendingTasks();
            TextWebSocketFrame response = rejected.readOutbound();
            try { assertTrue(response.text().contains("\"errorCode\":\"ROOM_ACCESS_DENIED\"")); }
            finally { response.release(); }
            assertTrue(rejected.isActive());
        } finally { rejected.finishAndReleaseAll(); }
        EmbeddedChannel malformed = channel(query -> { throw new AssertionError(); }, Runnable::run);
        try {
            authenticate(malformed); malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"HISTORY_REQ\",\"data\":{\"roomId\":1.5}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release(); assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }
        EmbeddedChannel saturated = channel(query -> LegacyV1RoomHistoryResult.Rejected.INVALID_REQUEST,
                command -> { throw new RejectedExecutionException(); });
        try {
            authenticate(saturated); saturated.writeInbound(request());
            ((CloseWebSocketFrame) saturated.readOutbound()).release(); assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(LegacyV1RoomHistoryUseCase useCase,
            java.util.concurrent.Executor executor) {
        return new EmbeddedChannel(new V1RoomHistoryHandler(useCase,
                new V1JsonRoomHistoryCodec(Clock.fixed(NOW, ZoneOffset.UTC)), executor,
                V1RoomHistoryEventSink.noop()));
    }
    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(IDENTITY);
    }
    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame("{\"type\":\"HISTORY_REQ\",\"data\":{\"roomId\":7}} ");
    }
}
