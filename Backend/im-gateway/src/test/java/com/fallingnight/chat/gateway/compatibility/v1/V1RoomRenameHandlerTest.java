package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

final class V1RoomRenameHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test void bindsActorAndRoutesChangedEffectsToAllMappedMembers() {
        UUID actor = UUID.randomUUID(), peerId = UUID.randomUUID(), conversation = UUID.randomUUID();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel peer = new EmbeddedChannel(); registry.replace(peerId, peer);
        EmbeddedChannel sender = new EmbeddedChannel(new V1RoomRenameHandler(command -> {
            assertEquals(actor, command.actorAccountId()); assertEquals(7, command.legacyRoomId());
            assertEquals("New Room", command.newName());
            return new LegacyV1RoomRenameResult.Renamed(
                    conversation, 7, "Old", "New Room", true, NOW);
        }, new LegacyV1RoomAudienceService((room, candidates) -> Set.of(actor, peerId)),
                codec(), registry, Runnable::run, V1RoomRenameEventSink.noop()));
        try {
            sender.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
            registry.replace(actor, sender); sender.writeInbound(request()); sender.runPendingTasks();
            TextWebSocketFrame response = sender.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"RENAME_ROOM_RSP\""));
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"changed\":true"));
                assertTrue(response.text().contains("\"newName\":\"New Room\""));
                assertFalse(response.text().contains(conversation.toString()));
            } finally { response.release(); }
            sender.runPendingTasks();
            TextWebSocketFrame ownRename = sender.readOutbound(), ownSystem = sender.readOutbound();
            peer.runPendingTasks();
            TextWebSocketFrame peerRename = peer.readOutbound(), peerSystem = peer.readOutbound();
            try {
                assertTrue(ownRename.text().contains("RENAME_ROOM_NOTIFY"));
                assertTrue(peerRename.text().contains("RENAME_ROOM_NOTIFY"));
                assertTrue(ownSystem.text().contains("SYSTEM_MSG"));
                assertTrue(peerSystem.text().contains("管理员 Owner"));
            } finally {
                ownRename.release(); ownSystem.release(); peerRename.release(); peerSystem.release();
            }
        } finally { sender.finishAndReleaseAll(); peer.finishAndReleaseAll(); }
    }

    @Test void unchangedAndRejectionDoNotNotifyAndMalformedOrSaturatedClose() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        EmbeddedChannel unchanged = channel(actor, command ->
                new LegacyV1RoomRenameResult.Renamed(
                        conversation, 7, "New Room", "New Room", false, NOW), Runnable::run);
        try {
            unchanged.writeInbound(request()); unchanged.runPendingTasks();
            TextWebSocketFrame response = unchanged.readOutbound();
            try { assertTrue(response.text().contains("\"changed\":false")); }
            finally { response.release(); }
            assertNull(unchanged.readOutbound());
        } finally { unchanged.finishAndReleaseAll(); }

        EmbeddedChannel rejected = channel(actor, command ->
                LegacyV1RoomRenameResult.Rejected.ROOM_ADMIN_REQUIRED, Runnable::run);
        try {
            rejected.writeInbound(request()); rejected.runPendingTasks();
            TextWebSocketFrame response = rejected.readOutbound();
            try { assertTrue(response.text().contains("ROOM_ADMIN_REQUIRED")); }
            finally { response.release(); }
            assertTrue(rejected.isActive());
        } finally { rejected.finishAndReleaseAll(); }

        EmbeddedChannel malformed = channel(actor, command -> { throw new AssertionError(); },
                Runnable::run);
        try {
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"RENAME_ROOM_REQ\",\"data\":{\"roomId\":7}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel(actor, command ->
                LegacyV1RoomRenameResult.Rejected.INVALID_INPUT,
                task -> { throw new RejectedExecutionException(); });
        try {
            saturated.writeInbound(request());
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(UUID actor, LegacyV1RoomRenameUseCase rename,
            java.util.concurrent.Executor executor) {
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel(new V1RoomRenameHandler(rename,
                new LegacyV1RoomAudienceService((room, candidates) -> Set.of()), codec(),
                registry, executor, V1RoomRenameEventSink.noop()));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
        registry.replace(actor, channel); return channel;
    }
    private static V1JsonRoomRenameCodec codec() {
        return new V1JsonRoomRenameCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static LegacyV1AuthenticatedIdentity identity(UUID actor) {
        return new LegacyV1AuthenticatedIdentity(1, actor, UUID.randomUUID(), UUID.randomUUID(),
                NOW.plusSeconds(60), "owner", "Owner", false);
    }
    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame("{\"type\":\"RENAME_ROOM_REQ\","
                + "\"data\":{\"roomId\":7,\"newName\":\"New Room\"}}" );
    }
}
