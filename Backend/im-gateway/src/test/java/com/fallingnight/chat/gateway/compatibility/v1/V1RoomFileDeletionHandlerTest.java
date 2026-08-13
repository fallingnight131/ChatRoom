package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

final class V1RoomFileDeletionHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test void bindsActorEncodesResponseAndRoutesBothFirstOnlyNotifications() {
        UUID actor = UUID.randomUUID(), peerId = UUID.randomUUID(), outsider = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel peer = new EmbeddedChannel(), outside = new EmbeddedChannel();
        registry.replace(peerId, peer); registry.replace(outsider, outside);
        EmbeddedChannel sender = new EmbeddedChannel(new V1RoomFileDeletionHandler(command -> {
            assertEquals(actor, command.actorAccountId()); assertEquals(7, command.legacyRoomId());
            assertEquals("delete-1", command.clientOperationId());
            assertEquals(List.of(9L, 10L), command.legacyFileIds());
            return new LegacyV1RoomFileDeletionResult.Deleted(false, conversation, 7,
                    "delete-1", List.of(701L, 702L), List.of(9L, 10L), 12,
                    NOW, 100, 1000);
        }, new LegacyV1RoomAudienceService((room, candidates) -> {
            assertEquals(conversation, room); return Set.of(peerId);
        }), new V1JsonRoomFileDeletionCodec(Clock.fixed(NOW, ZoneOffset.UTC)), registry,
                Runnable::run, V1RoomFileDeletionEventSink.noop()));
        try {
            sender.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
            registry.replace(actor, sender); sender.writeInbound(request()); sender.runPendingTasks();
            TextWebSocketFrame response = sender.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"ROOM_FILES_DELETE_RSP\""));
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"deletedFileIds\":[9,10]"));
                assertTrue(response.text().contains("\"messageIds\":[701,702]"));
                assertTrue(response.text().contains("\"syncSequence\":12"));
                assertTrue(response.text().contains("\"usedFileSpace\":100"));
            } finally { response.release(); }
            assertNull(sender.readOutbound());
            peer.runPendingTasks();
            TextWebSocketFrame deletion = peer.readOutbound(), files = peer.readOutbound();
            try {
                assertTrue(deletion.text().contains("\"type\":\"DELETE_MSGS_NOTIFY\""));
                assertTrue(deletion.text().contains("\"operator\":\"Owner\""));
                assertTrue(files.text().contains("\"type\":\"ROOM_FILES_NOTIFY\""));
                assertTrue(files.text().contains("\"deletedFileIds\":[9,10]"));
            } finally { deletion.release(); files.release(); }
            outside.runPendingTasks(); assertNull(outside.readOutbound());
        } finally {
            sender.finishAndReleaseAll(); peer.finishAndReleaseAll(); outside.finishAndReleaseAll();
        }
    }

    @Test void duplicateAndRejectionDoNotNotifyAndMalformedOrSaturatedClose() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        EmbeddedChannel duplicate = channel(actor, command ->
                new LegacyV1RoomFileDeletionResult.Deleted(true, conversation, 7,
                        "delete-1", List.of(701L), List.of(9L), 12, NOW, 0, 1000),
                Runnable::run);
        try {
            duplicate.writeInbound(request()); duplicate.runPendingTasks();
            TextWebSocketFrame response = duplicate.readOutbound();
            try { assertTrue(response.text().contains("\"duplicate\":true")); }
            finally { response.release(); }
            assertNull(duplicate.readOutbound());
        } finally { duplicate.finishAndReleaseAll(); }

        EmbeddedChannel rejected = channel(actor, command ->
                LegacyV1RoomFileDeletionResult.Rejected.ROOM_ADMIN_REQUIRED, Runnable::run);
        try {
            rejected.writeInbound(request()); rejected.runPendingTasks();
            TextWebSocketFrame response = rejected.readOutbound();
            try { assertTrue(response.text().contains("ADMIN_DELETE_ACCESS_DENIED")); }
            finally { response.release(); }
            assertTrue(rejected.isActive());
        } finally { rejected.finishAndReleaseAll(); }

        EmbeddedChannel malformed = channel(actor, command -> { throw new AssertionError(); },
                Runnable::run);
        try {
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"ROOM_FILES_DELETE_REQ\",\"data\":{"
                            + "\"roomId\":7,\"fileIds\":[9],\"deleteAll\":true}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel(actor, command ->
                LegacyV1RoomFileDeletionResult.Rejected.INVALID_INPUT,
                task -> { throw new RejectedExecutionException(); });
        try {
            saturated.writeInbound(request());
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(UUID actor,
            LegacyV1RoomFileDeletionUseCase deletion, java.util.concurrent.Executor executor) {
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel(new V1RoomFileDeletionHandler(deletion,
                new LegacyV1RoomAudienceService((room, candidates) -> Set.of()),
                new V1JsonRoomFileDeletionCodec(Clock.fixed(NOW, ZoneOffset.UTC)), registry,
                executor, V1RoomFileDeletionEventSink.noop()));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
        registry.replace(actor, channel); return channel;
    }
    private static LegacyV1AuthenticatedIdentity identity(UUID actor) {
        return new LegacyV1AuthenticatedIdentity(1, actor, UUID.randomUUID(), UUID.randomUUID(),
                NOW.plusSeconds(60), "owner", "Owner", false);
    }
    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame("{\"type\":\"ROOM_FILES_DELETE_REQ\","
                + "\"id\":\"envelope\",\"data\":{\"roomId\":7,"
                + "\"fileIds\":[9,10],\"clientOperationId\":\"delete-1\"}}");
    }
}
