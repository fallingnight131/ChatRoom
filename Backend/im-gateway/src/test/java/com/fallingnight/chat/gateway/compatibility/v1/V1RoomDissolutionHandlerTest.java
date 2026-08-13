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

final class V1RoomDissolutionHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test void bindsActorUsesAuthoritativeNameAndRoutesOnlyFirstCommit() {
        UUID actor = UUID.randomUUID(), peerId = UUID.randomUUID(), conversation = UUID.randomUUID();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel peer = new EmbeddedChannel(); registry.replace(peerId, peer);
        EmbeddedChannel sender = new EmbeddedChannel(new V1RoomDissolutionHandler(
                (account, room) -> {
                    assertEquals(actor, account); assertEquals(7, room);
                    return new LegacyV1RoomDissolutionResult.Dissolved(conversation, 7,
                            "Authoritative Room", Set.of(actor, peerId), true, NOW);
                }, codec(), registry, Runnable::run, V1RoomDissolutionEventSink.noop()));
        try {
            sender.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
            registry.replace(actor, sender); sender.writeInbound(request()); sender.runPendingTasks();
            TextWebSocketFrame response = sender.readOutbound();
            try {
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"roomName\":\"Authoritative Room\""));
                assertFalse(response.text().contains("Spoofed"));
                assertFalse(response.text().contains(conversation.toString()));
            } finally { response.release(); }
            sender.runPendingTasks(); peer.runPendingTasks();
            TextWebSocketFrame own = sender.readOutbound(), remote = peer.readOutbound();
            try {
                assertTrue(own.text().contains("DELETE_ROOM_NOTIFY"));
                assertTrue(remote.text().contains("\"operator\":\"Owner\""));
                assertFalse(remote.text().contains("Spoofed"));
            } finally { own.release(); remote.release(); }
        } finally { sender.finishAndReleaseAll(); peer.finishAndReleaseAll(); }
    }

    @Test void retryAndRejectionDoNotNotifyWhileMalformedAndSaturationClose() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        EmbeddedChannel retry = channel(actor, (account, room) ->
                new LegacyV1RoomDissolutionResult.Dissolved(
                        conversation, 7, "Room", Set.of(), false, NOW), Runnable::run);
        try {
            retry.writeInbound(request()); retry.runPendingTasks();
            TextWebSocketFrame response = retry.readOutbound();
            try { assertTrue(response.text().contains("\"changed\":false")); }
            finally { response.release(); }
            assertNull(retry.readOutbound()); assertTrue(retry.isActive());
        } finally { retry.finishAndReleaseAll(); }

        EmbeddedChannel rejected = channel(actor, (account, room) ->
                LegacyV1RoomDissolutionResult.Rejected.ROOM_ADMIN_REQUIRED, Runnable::run);
        try {
            rejected.writeInbound(request()); rejected.runPendingTasks();
            TextWebSocketFrame response = rejected.readOutbound();
            try { assertTrue(response.text().contains("ROOM_ADMIN_REQUIRED")); }
            finally { response.release(); }
            assertTrue(rejected.isActive()); assertNull(rejected.readOutbound());
        } finally { rejected.finishAndReleaseAll(); }

        EmbeddedChannel malformed = channel(actor, (account, room) -> { throw new AssertionError(); },
                Runnable::run);
        try {
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"DELETE_ROOM_REQ\",\"data\":{}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release(); assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel(actor, (account, room) -> { throw new AssertionError(); },
                task -> { throw new RejectedExecutionException(); });
        try {
            saturated.writeInbound(request());
            ((CloseWebSocketFrame) saturated.readOutbound()).release(); assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    @Test void strictCodecPassesOtherFramesAndRejectsUnknownDeleteData() {
        V1JsonRoomDissolutionCodec codec = codec();
        assertEquals(V1JsonRoomDissolutionCodec.RequestKind.OTHER,
                codec.decode("{\"type\":\"PING\"}".getBytes()).kind());
        assertEquals(V1JsonRoomDissolutionCodec.RequestKind.MALFORMED,
                codec.decode(("{\"type\":\"DELETE_ROOM_REQ\",\"data\":{"
                        + "\"roomId\":7,\"extra\":true}}").getBytes()).kind());
    }

    private static EmbeddedChannel channel(UUID actor, LegacyV1RoomDissolutionUseCase rooms,
            java.util.concurrent.Executor executor) {
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel result = new EmbeddedChannel(new V1RoomDissolutionHandler(
                rooms, codec(), registry, executor, V1RoomDissolutionEventSink.noop()));
        result.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
        registry.replace(actor, result); return result;
    }
    private static V1JsonRoomDissolutionCodec codec() {
        return new V1JsonRoomDissolutionCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static LegacyV1AuthenticatedIdentity identity(UUID actor) {
        return new LegacyV1AuthenticatedIdentity(1, actor, UUID.randomUUID(), UUID.randomUUID(),
                NOW.plusSeconds(60), "owner", "Owner", false);
    }
    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame("{\"type\":\"DELETE_ROOM_REQ\",\"data\":{"
                + "\"roomId\":7,\"roomName\":\"Spoofed\"}}" );
    }
}
