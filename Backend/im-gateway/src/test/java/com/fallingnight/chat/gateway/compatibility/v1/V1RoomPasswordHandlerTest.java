package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class V1RoomPasswordHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test void codecOwnsSecretAndRejectsAmbiguousFrames() {
        V1JsonRoomPasswordCodec codec = codec();
        var set = codec.decode(bytes("{\"type\":\"SET_ROOM_PASSWORD_REQ\","
                + "\"data\":{\"roomId\":7,\"password\":\"密码🔐\"}}"));
        assertEquals(V1JsonRoomPasswordCodec.RequestKind.SET, set.kind());
        assertArrayEquals("密码🔐".getBytes(StandardCharsets.UTF_8), set.passwordCopy());
        assertFalse(set.isClosed());
        set.close();
        assertTrue(set.isClosed());
        assertThrows(IllegalStateException.class, set::passwordCopy);

        try (var clear = codec.decode(bytes("{\"type\":\"SET_ROOM_PASSWORD_REQ\","
                + "\"data\":{\"roomId\":7,\"password\":\"\"}}"))) {
            assertEquals(V1JsonRoomPasswordCodec.RequestKind.SET, clear.kind());
            assertArrayEquals(new byte[0], clear.passwordCopy());
        }
        assertEquals(V1JsonRoomPasswordCodec.RequestKind.STATUS, codec.decode(bytes(
                "{\"type\":\"GET_ROOM_PASSWORD_REQ\",\"data\":{\"roomId\":7}}" )).kind());
        assertEquals(V1JsonRoomPasswordCodec.RequestKind.MALFORMED, codec.decode(bytes(
                "{\"type\":\"SET_ROOM_PASSWORD_REQ\",\"data\":{\"roomId\":7}}" )).kind());
        assertEquals(V1JsonRoomPasswordCodec.RequestKind.MALFORMED, codec.decode(bytes(
                "{\"type\":\"GET_ROOM_PASSWORD_REQ\",\"data\":{\"roomId\":7,\"password\":\"x\"}}" )).kind());
        assertEquals(V1JsonRoomPasswordCodec.RequestKind.MALFORMED, codec.decode(bytes(
                "{\"type\":\"SET_ROOM_PASSWORD_REQ\",\"type\":\"SET_ROOM_PASSWORD_REQ\"}" )).kind());
        assertEquals(V1JsonRoomPasswordCodec.RequestKind.OTHER,
                codec.decode(new byte[V1JsonRoomPasswordCodec.MAX_REQUEST_BYTES + 1]).kind());
    }

    @Test void bindsActorClearsCommandAndRoutesOnlyChangedNotificationWithoutSecrets() {
        UUID actor = UUID.randomUUID(), peerId = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicReference<LegacyV1RoomPasswordCommand> captured = new AtomicReference<>();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel peer = new EmbeddedChannel(); registry.replace(peerId, peer);
        EmbeddedChannel sender = new EmbeddedChannel(new V1RoomPasswordHandler(
                (account, room) -> { throw new AssertionError(); }, command -> {
                    assertEquals(actor, command.actorAccountId()); assertEquals(7, command.legacyRoomId());
                    assertFalse(command.clearsPassword()); captured.set(command);
                    return new LegacyV1RoomPasswordUpdateResult.Updated(
                            conversation, 7, true, true, NOW);
                }, new LegacyV1RoomAudienceService((room, candidates) -> Set.of(actor, peerId)),
                codec(), registry, Runnable::run, V1RoomPasswordEventSink.noop()));
        try {
            sender.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
            registry.replace(actor, sender);
            sender.writeInbound(setRequest("super-secret")); sender.runPendingTasks();
            assertTrue(captured.get().isClosed());
            TextWebSocketFrame response = sender.readOutbound();
            try {
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"hasPassword\":true"));
                assertTrue(response.text().contains("\"changed\":true"));
                assertSecretAbsent(response.text());
            } finally { response.release(); }
            sender.runPendingTasks(); peer.runPendingTasks();
            TextWebSocketFrame own = sender.readOutbound(), remote = peer.readOutbound();
            try {
                assertTrue(own.text().contains("SYSTEM_MSG"));
                assertTrue(remote.text().contains("已设置/修改聊天室密码"));
                assertSecretAbsent(own.text()); assertSecretAbsent(remote.text());
            } finally { own.release(); remote.release(); }
        } finally { sender.finishAndReleaseAll(); peer.finishAndReleaseAll(); }
    }

    @Test void statusAndUnchangedOrRejectedMutationsStayConnectedAndDoNotLeak() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        EmbeddedChannel status = channel(actor,
                (account, room) -> new LegacyV1RoomPasswordStatusResult.Authorized(
                        conversation, 7, true, NOW), command -> { throw new AssertionError(); }, Runnable::run);
        try {
            status.writeInbound(statusRequest()); status.runPendingTasks();
            TextWebSocketFrame response = status.readOutbound();
            try {
                assertTrue(response.text().contains("GET_ROOM_PASSWORD_RSP"));
                assertTrue(response.text().contains("\"hasPassword\":true"));
                assertSecretAbsent(response.text());
            } finally { response.release(); }
            assertTrue(status.isActive()); assertNull(status.readOutbound());
        } finally { status.finishAndReleaseAll(); }

        EmbeddedChannel unchanged = channel(actor, (account, room) -> { throw new AssertionError(); },
                command -> new LegacyV1RoomPasswordUpdateResult.Updated(
                        conversation, 7, true, false, NOW), Runnable::run);
        try {
            unchanged.writeInbound(setRequest("super-secret")); unchanged.runPendingTasks();
            TextWebSocketFrame response = unchanged.readOutbound();
            try { assertTrue(response.text().contains("\"changed\":false")); }
            finally { response.release(); }
            assertNull(unchanged.readOutbound()); assertTrue(unchanged.isActive());
        } finally { unchanged.finishAndReleaseAll(); }

        EmbeddedChannel rejected = channel(actor, (account, room) ->
                LegacyV1RoomPasswordStatusResult.Rejected.ROOM_ADMIN_REQUIRED,
                command -> LegacyV1RoomPasswordUpdateResult.Rejected.ROOM_ADMIN_REQUIRED,
                Runnable::run);
        try {
            rejected.writeInbound(setRequest("super-secret")); rejected.runPendingTasks();
            TextWebSocketFrame response = rejected.readOutbound();
            try { assertTrue(response.text().contains("ROOM_ADMIN_REQUIRED")); assertSecretAbsent(response.text()); }
            finally { response.release(); }
            assertTrue(rejected.isActive()); assertNull(rejected.readOutbound());
        } finally { rejected.finishAndReleaseAll(); }
    }

    @Test void malformedAndSaturatedRequestsCloseTheConnection() {
        UUID actor = UUID.randomUUID();
        EmbeddedChannel malformed = channel(actor, (account, room) -> { throw new AssertionError(); },
                command -> { throw new AssertionError(); }, Runnable::run);
        try {
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"SET_ROOM_PASSWORD_REQ\",\"data\":{\"roomId\":7}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release(); assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel(actor, (account, room) -> { throw new AssertionError(); },
                command -> { throw new AssertionError(); }, task -> { throw new RejectedExecutionException(); });
        try {
            saturated.writeInbound(statusRequest());
            ((CloseWebSocketFrame) saturated.readOutbound()).release(); assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(UUID actor, LegacyV1RoomPasswordStatusUseCase status,
            LegacyV1RoomPasswordUpdateUseCase update, java.util.concurrent.Executor executor) {
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel(new V1RoomPasswordHandler(status, update,
                new LegacyV1RoomAudienceService((room, candidates) -> Set.of()), codec(),
                registry, executor, V1RoomPasswordEventSink.noop()));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
        registry.replace(actor, channel); return channel;
    }
    private static V1JsonRoomPasswordCodec codec() {
        return new V1JsonRoomPasswordCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static LegacyV1AuthenticatedIdentity identity(UUID actor) {
        return new LegacyV1AuthenticatedIdentity(1, actor, UUID.randomUUID(), UUID.randomUUID(),
                NOW.plusSeconds(60), "owner", "Owner", false);
    }
    private static TextWebSocketFrame setRequest(String password) {
        return new TextWebSocketFrame("{\"type\":\"SET_ROOM_PASSWORD_REQ\","
                + "\"data\":{\"roomId\":7,\"password\":\"" + password + "\"}}" );
    }
    private static TextWebSocketFrame statusRequest() {
        return new TextWebSocketFrame(
                "{\"type\":\"GET_ROOM_PASSWORD_REQ\",\"data\":{\"roomId\":7}}" );
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void assertSecretAbsent(String wire) {
        assertFalse(wire.contains("super-secret")); assertFalse(wire.contains("argon2"));
        assertFalse(wire.contains("idempotency")); assertFalse(wire.contains("passwordHash"));
    }
}
