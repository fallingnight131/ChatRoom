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

final class V1RoomMessageDeletionHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test void decodesAllFourStrictShapesAndEnvelopeOperationFallback() {
        var codec = codec();
        var selected = codec.decode(bytes("{\"type\":\"DELETE_MSGS_REQ\",\"id\":\"fallback\","
                + "\"data\":{\"roomId\":7,\"mode\":\"selected\",\"messageIds\":[9,3]}}"));
        assertEquals(V1JsonRoomMessageDeletionCodec.RequestKind.DELETE, selected.kind());
        assertEquals("fallback", selected.clientOperationId());
        assertEquals(List.of(9L, 3L), selected.messageIds());
        for (String mode : List.of("all", "before", "after")) {
            String timestamp = mode.equals("all") ? "" : ",\"timestamp\":12999";
            var request = codec.decode(bytes("{\"type\":\"DELETE_MSGS_REQ\","
                    + "\"data\":{\"roomId\":7,\"mode\":\"" + mode
                    + "\",\"clientOperationId\":\"operation\"" + timestamp + "}}"));
            assertEquals(V1JsonRoomMessageDeletionCodec.RequestKind.DELETE, request.kind());
            assertEquals(mode, request.mode());
        }
        assertEquals(V1JsonRoomMessageDeletionCodec.RequestKind.MALFORMED,
                codec.decode(bytes("{\"type\":\"DELETE_MSGS_REQ\",\"data\":{"
                        + "\"roomId\":7,\"mode\":\"all\",\"messageIds\":[]}}"))
                        .kind());
    }

    @Test void bindsActorRespondsThenRoutesPeerDeletionAndRoomSystemMessage() {
        UUID actor = UUID.randomUUID(), peerId = UUID.randomUUID(), outsider = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel peer = new EmbeddedChannel(), outside = new EmbeddedChannel();
        registry.replace(peerId, peer); registry.replace(outsider, outside);
        EmbeddedChannel sender = new EmbeddedChannel(new V1RoomMessageDeletionHandler(command -> {
            assertEquals(actor, command.actorAccountId()); assertEquals(7, command.legacyRoomId());
            assertEquals("delete-1", command.clientOperationId());
            assertEquals("selected", command.mode()); assertEquals(List.of(9L), command.legacyMessageIds());
            return new LegacyV1RoomMessageDeletionResult.Deleted(false, conversation, 7,
                    "delete-1", LegacyV1RoomMessageDeletionMode.SELECTED, List.of(9L),
                    List.of(90L), 0, 1, 12, NOW);
        }, new LegacyV1RoomAudienceService((room, candidates) -> {
            assertEquals(conversation, room); return Set.of(actor, peerId);
        }), codec(), registry, Runnable::run, V1RoomMessageDeletionEventSink.noop()));
        try {
            sender.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
            registry.replace(actor, sender);
            sender.writeInbound(request()); sender.runPendingTasks();
            TextWebSocketFrame response = sender.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"DELETE_MSGS_RSP\""));
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"messageIds\":[9]"));
                assertTrue(response.text().contains("\"deletedFileIds\":[90]"));
                assertTrue(response.text().contains("\"syncSequence\":12"));
            } finally { response.release(); }
            sender.runPendingTasks(); TextWebSocketFrame ownSystem = sender.readOutbound();
            try {
                assertTrue(ownSystem.text().contains("\"type\":\"SYSTEM_MSG\""));
                assertFalse(ownSystem.text().contains("DELETE_MSGS_NOTIFY"));
            } finally { ownSystem.release(); }
            peer.runPendingTasks();
            TextWebSocketFrame deletion = peer.readOutbound(), system = peer.readOutbound();
            try {
                assertTrue(deletion.text().contains("\"type\":\"DELETE_MSGS_NOTIFY\""));
                assertTrue(deletion.text().contains("\"operator\":\"Owner\""));
                assertTrue(system.text().contains("删除了 1 条消息"));
            } finally { deletion.release(); system.release(); }
            outside.runPendingTasks(); assertNull(outside.readOutbound());
        } finally {
            sender.finishAndReleaseAll(); peer.finishAndReleaseAll(); outside.finishAndReleaseAll();
        }
    }

    @Test void duplicateAndRejectedRequestsDoNotNotifyAndFailuresClose() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        EmbeddedChannel duplicate = channel(actor, command ->
                new LegacyV1RoomMessageDeletionResult.Deleted(true, conversation, 7,
                        "delete-1", LegacyV1RoomMessageDeletionMode.SELECTED, List.of(9L),
                        List.of(), 0, 1, 12, NOW), Runnable::run);
        try {
            duplicate.writeInbound(request()); duplicate.runPendingTasks();
            TextWebSocketFrame response = duplicate.readOutbound();
            try { assertTrue(response.text().contains("\"duplicate\":true")); }
            finally { response.release(); }
            assertNull(duplicate.readOutbound());
        } finally { duplicate.finishAndReleaseAll(); }

        EmbeddedChannel rejected = channel(actor, command ->
                LegacyV1RoomMessageDeletionResult.Rejected.DELETE_SCOPE_TOO_LARGE,
                Runnable::run);
        try {
            rejected.writeInbound(request()); rejected.runPendingTasks();
            TextWebSocketFrame response = rejected.readOutbound();
            try { assertTrue(response.text().contains("DELETE_SCOPE_TOO_LARGE")); }
            finally { response.release(); }
            assertTrue(rejected.isActive());
        } finally { rejected.finishAndReleaseAll(); }

        EmbeddedChannel malformed = channel(actor, command -> { throw new AssertionError(); },
                Runnable::run);
        try {
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"DELETE_MSGS_REQ\",\"data\":{\"roomId\":7}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel(actor, command ->
                LegacyV1RoomMessageDeletionResult.Rejected.INVALID_INPUT,
                task -> { throw new RejectedExecutionException(); });
        try {
            saturated.writeInbound(request());
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(UUID actor,
            LegacyV1RoomMessageDeletionUseCase deletion,
            java.util.concurrent.Executor executor) {
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel(new V1RoomMessageDeletionHandler(deletion,
                new LegacyV1RoomAudienceService((room, candidates) -> Set.of()), codec(),
                registry, executor, V1RoomMessageDeletionEventSink.noop()));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
        registry.replace(actor, channel); return channel;
    }

    private static V1JsonRoomMessageDeletionCodec codec() {
        return new V1JsonRoomMessageDeletionCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static LegacyV1AuthenticatedIdentity identity(UUID actor) {
        return new LegacyV1AuthenticatedIdentity(1, actor, UUID.randomUUID(), UUID.randomUUID(),
                NOW.plusSeconds(60), "owner", "Owner", false);
    }
    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame("{\"type\":\"DELETE_MSGS_REQ\","
                + "\"data\":{\"roomId\":7,\"mode\":\"selected\","
                + "\"messageIds\":[9],\"clientOperationId\":\"delete-1\"}}" );
    }
    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
