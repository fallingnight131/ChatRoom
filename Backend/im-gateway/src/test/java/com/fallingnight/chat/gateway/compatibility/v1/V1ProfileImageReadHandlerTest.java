package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.profile.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.time.*;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class V1ProfileImageReadHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};

    @Test void bindsAccountActorEmitsCompatibleBase64AndClosesPayload() {
        UUID actor = UUID.randomUUID();
        AtomicReference<ProfileImageReadTarget> target = new AtomicReference<>();
        ProfileImageObjectPayload payload = ProfileImageObjectPayload.copyOf(PNG);
        EmbeddedChannel channel = channel(actor, value -> {
            target.set(value);
            return new ProfileImageLoadResult.Loaded(payload, 16, 24, 3, NOW);
        }, Runnable::run);
        try {
            channel.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"AVATAR_GET_REQ\",\"data\":{\"username\":\"peer\"}}"));
            channel.runPendingTasks();
            var account = assertInstanceOf(ProfileImageReadTarget.AccountByUsername.class,
                    target.get());
            assertEquals(actor, account.actorAccountId()); assertEquals("peer", account.username());
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"AVATAR_GET_RSP\""));
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"avatarData\":\""
                        + Base64.getEncoder().encodeToString(PNG) + "\""));
                assertTrue(response.text().contains("\"version\":3"));
                assertFalse(response.text().contains("avatars/sha256"));
            } finally { response.release(); }
            assertThrows(IllegalStateException.class, payload::byteSize);
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void bindsRoomActorAndMapsDeniedToCompatibleMissingShape() {
        UUID actor = UUID.randomUUID();
        AtomicReference<ProfileImageReadTarget> target = new AtomicReference<>();
        EmbeddedChannel channel = channel(actor, value -> {
            target.set(value); return ProfileImageLoadResult.Rejected.ACCESS_DENIED;
        }, Runnable::run);
        try {
            channel.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"ROOM_AVATAR_GET_REQ\",\"data\":{\"roomId\":7}}"));
            channel.runPendingTasks();
            var room = assertInstanceOf(ProfileImageReadTarget.LegacyRoom.class, target.get());
            assertEquals(actor, room.actorAccountId()); assertEquals(7, room.legacyRoomId());
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"ROOM_AVATAR_GET_RSP\""));
                assertTrue(response.text().contains("\"roomId\":7"));
                assertTrue(response.text().contains("\"success\":false"));
                assertFalse(response.text().contains("avatarData"));
            } finally { response.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void duplicateUnknownDataOrSaturationFailsClosed() {
        UUID actor = UUID.randomUUID();
        for (String malformed : List.of(
                "{\"type\":\"AVATAR_GET_REQ\",\"data\":{}}",
                "{\"type\":\"AVATAR_GET_REQ\",\"data\":{\"username\":\"a\",\"username\":\"b\"}}",
                "{\"type\":\"ROOM_AVATAR_GET_REQ\",\"data\":{\"roomId\":7,\"extra\":1}}")) {
            EmbeddedChannel channel = channel(actor, value -> { throw new AssertionError(); },
                    Runnable::run);
            try {
                channel.writeInbound(new TextWebSocketFrame(malformed));
                ((CloseWebSocketFrame) channel.readOutbound()).release();
                assertFalse(channel.isActive());
            } finally { channel.finishAndReleaseAll(); }
        }
        EmbeddedChannel saturated = channel(actor,
                value -> ProfileImageLoadResult.Missing.INSTANCE,
                task -> { throw new RejectedExecutionException(); });
        try {
            saturated.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"AVATAR_GET_REQ\",\"data\":{\"username\":\"peer\"}}"));
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    @Test void storageIntegrityFailureClosesWithoutFalseMissingResponse() {
        UUID actor = UUID.randomUUID();
        EmbeddedChannel channel = channel(actor, value -> {
            throw new ProfileImageIntegrityException("mismatch");
        }, Runnable::run);
        try {
            channel.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"AVATAR_GET_REQ\",\"data\":{\"username\":\"peer\"}}"));
            channel.runPendingTasks();
            assertInstanceOf(CloseWebSocketFrame.class, channel.readOutbound()).release();
            assertFalse(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(UUID actor, ProfileImageLoadUseCase useCase,
            java.util.concurrent.Executor executor) {
        EmbeddedChannel channel = new EmbeddedChannel(new V1ProfileImageReadHandler(
                useCase, new V1JsonProfileImageReadCodec(
                        Clock.fixed(NOW, ZoneOffset.UTC)), executor,
                V1ProfileImageReadEventSink.noop()));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(new LegacyV1AuthenticatedIdentity(
                1, actor, UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(60),
                "owner", "Owner", false));
        return channel;
    }
}
