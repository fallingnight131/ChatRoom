package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class V1RoomCreationHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();

    @Test void codecOwnsUtf8PasswordBytesAndClearsThemOnClose() {
        V1JsonRoomCreationCodec codec = new V1JsonRoomCreationCodec(
                Clock.fixed(NOW, ZoneOffset.UTC));
        var decoded = codec.decode(("{\"type\":\"CREATE_ROOM_REQ\",\"id\":\"utf8\","
                + "\"data\":{\"roomName\":\"Room\",\"password\":\"\u5bc6\u7801secret\"}}")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(V1JsonRoomCreationCodec.RequestKind.CREATE, decoded.kind());
        assertEquals("\u5bc6\u7801secret", new String(decoded.passwordCopy(), StandardCharsets.UTF_8));
        decoded.close(); assertNull(decoded.passwordCopy());
    }

    @Test void bindsActorEnvelopeIdHashesPasswordAndReturnsUuidFreeResult() {
        AtomicReference<LegacyV1RoomCreationIntent> captured = new AtomicReference<>();
        AtomicReference<String> password = new AtomicReference<>();
        LegacyV1RoomCreationService service = new LegacyV1RoomCreationService(bytes -> {
            password.set(new String(bytes, StandardCharsets.UTF_8));
            return new LegacyV1RoomPasswordEncoding("$argon2id$fixture",
                    "hmac-sha256:v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        }, intent -> {
            captured.set(intent);
            return new LegacyV1RoomCreationResult.Created(UUID.randomUUID(), 7,
                    intent.roomName(), intent.actorAccountId(), false);
        });
        EmbeddedChannel channel = channel(service, Runnable::run);
        try {
            authenticate(channel); channel.writeInbound(request("request-7", "Room", "secret"));
            channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"CREATE_ROOM_RSP\""));
                assertTrue(response.text().contains("\"roomId\":7"));
                assertTrue(response.text().contains("\"roomName\":\"Room\""));
                assertTrue(response.text().contains("\"isAdmin\":true"));
                assertTrue(response.text().contains("\"duplicate\":false"));
                assertFalse(response.text().contains("conversationId"));
            } finally { response.release(); }
            assertEquals(ACTOR, captured.get().actorAccountId());
            assertEquals("request-7", captured.get().clientRequestId());
            assertEquals("secret", password.get());
            TextWebSocketFrame other = new TextWebSocketFrame(
                    "{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}");
            assertTrue(channel.writeInbound(other));
            ((TextWebSocketFrame) channel.readInbound()).release();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void returnsBusinessRejectionsWithoutClosing() {
        EmbeddedChannel channel = channel(command ->
                LegacyV1RoomCreationResult.Rejected.CLIENT_REQUEST_ID_CONFLICT,
                Runnable::run);
        try {
            authenticate(channel); channel.writeInbound(request("same", "Room", null));
            channel.runPendingTasks(); TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"success\":false"));
                assertTrue(response.text().contains(
                        "\"errorCode\":\"CLIENT_REQUEST_ID_CONFLICT\""));
            } finally { response.release(); }
            assertTrue(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void malformedDependencyFailureAndSaturationClose() {
        EmbeddedChannel malformed = channel(command -> { throw new AssertionError(); }, Runnable::run);
        try {
            authenticate(malformed); malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"CREATE_ROOM_REQ\",\"id\":\"x\",\"data\":{"
                            + "\"roomName\":\"Room\",\"extra\":true}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }
        EmbeddedChannel failed = channel(command -> {
            throw new IllegalStateException("private");
        }, Runnable::run);
        try {
            authenticate(failed); failed.writeInbound(request("x", "Room", "secret"));
            failed.runPendingTasks(); ((CloseWebSocketFrame) failed.readOutbound()).release();
            assertFalse(failed.isActive());
        } finally { failed.finishAndReleaseAll(); }
        EmbeddedChannel saturated = channel(command ->
                LegacyV1RoomCreationResult.Rejected.CREATION_DENIED, task -> {
                    throw new RejectedExecutionException("full");
                });
        try {
            authenticate(saturated); saturated.writeInbound(request("x", "Room", "secret"));
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(LegacyV1RoomCreationUseCase useCase,
            java.util.concurrent.Executor executor) {
        return new EmbeddedChannel(new V1RoomCreationHandler(useCase,
                new V1JsonRoomCreationCodec(Clock.fixed(NOW, ZoneOffset.UTC)), executor,
                V1RoomCreationEventSink.noop()));
    }
    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(
                new LegacyV1AuthenticatedIdentity(42, ACTOR, UUID.randomUUID(), UUID.randomUUID(),
                        NOW.plusSeconds(60), "owner", "Owner", false));
    }
    private static TextWebSocketFrame request(String id, String roomName, String password) {
        return new TextWebSocketFrame("{\"type\":\"CREATE_ROOM_REQ\",\"id\":\"" + id
                + "\",\"data\":{\"roomName\":\"" + roomName + "\""
                + (password == null ? "" : ",\"password\":\"" + password + "\"") + "}}");
    }
}
