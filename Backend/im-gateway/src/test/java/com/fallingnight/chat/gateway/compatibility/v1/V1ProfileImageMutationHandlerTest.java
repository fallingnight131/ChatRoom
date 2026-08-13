package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.profile.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.time.*;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class V1ProfileImageMutationHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};

    @Test void bindsAccountStoresOwnedBytesAndRoutesFirstCommitToOtherSessions() {
        UUID actor = UUID.randomUUID(), peerId = UUID.randomUUID();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel peer = new EmbeddedChannel(); registry.replace(peerId, peer);
        AtomicReference<ProfileImageTarget> target = new AtomicReference<>();
        EmbeddedChannel sender = channel(actor, (value, upload) -> {
            target.set(value);
            assertArrayEquals(PNG, upload.withCopy(bytes -> bytes.clone())); upload.close();
            return committed(true, Set.of());
        }, registry, Runnable::run);
        registry.replace(actor, sender);
        try {
            sender.writeInbound(accountRequest()); sender.runPendingTasks();
            assertEquals(actor, assertInstanceOf(ProfileImageTarget.Account.class,
                    target.get()).actorAccountId());
            TextWebSocketFrame response = sender.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"AVATAR_UPLOAD_RSP\""));
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"changed\":true"));
            } finally { response.release(); }
            peer.runPendingTasks(); TextWebSocketFrame notification = peer.readOutbound();
            try {
                assertTrue(notification.text().contains("AVATAR_UPDATE_NOTIFY"));
                assertTrue(notification.text().contains("\"username\":\"owner\""));
                assertTrue(notification.text().contains(Base64.getEncoder().encodeToString(PNG)));
                assertFalse(notification.text().contains("avatars/sha256"));
            } finally { notification.release(); }
            assertNull(sender.readOutbound());
        } finally { sender.finishAndReleaseAll(); peer.finishAndReleaseAll(); }
    }

    @Test void bindsRoomAndReturnsCompatibleAuthorizationRejectionWithoutNotification() {
        UUID actor = UUID.randomUUID(); AtomicReference<ProfileImageTarget> target = new AtomicReference<>();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel channel = channel(actor, (value, upload) -> {
            target.set(value); upload.close();
            return ProfileImageMutationResult.Rejected.ROOM_ADMIN_REQUIRED;
        }, registry, Runnable::run);
        try {
            channel.writeInbound(roomRequest(7)); channel.runPendingTasks();
            var room = assertInstanceOf(ProfileImageTarget.LegacyRoom.class, target.get());
            assertEquals(actor, room.actorAccountId()); assertEquals(7, room.legacyRoomId());
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("ROOM_AVATAR_UPLOAD_RSP"));
                assertTrue(response.text().contains("\"roomId\":7"));
                assertTrue(response.text().contains("\"success\":false"));
                assertTrue(response.text().contains("只有管理员"));
            } finally { response.release(); }
            assertNull(channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void unchangedRetryReturnsSuccessWithoutBroadcast() {
        UUID actor = UUID.randomUUID(); V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel channel = channel(actor, (value, upload) -> {
            upload.close(); return committed(false, Set.of());
        }, registry, Runnable::run);
        try {
            channel.writeInbound(accountRequest()); channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"changed\":false"));
            } finally { response.release(); }
            assertNull(channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void malformedNonCanonicalBase64UnknownFieldsAndSaturationFailClosed() {
        UUID actor = UUID.randomUUID();
        for (String malformed : List.of(
                "{\"type\":\"AVATAR_UPLOAD_REQ\",\"data\":{\"avatarData\":\"YQ\"}}",
                "{\"type\":\"AVATAR_UPLOAD_REQ\",\"data\":{\"avatarData\":\"@@==\"}}",
                "{\"type\":\"ROOM_AVATAR_UPLOAD_REQ\",\"data\":{\"roomId\":7,\"avatarData\":\"YQ==\",\"extra\":1}}")) {
            EmbeddedChannel channel = channel(actor, (value, upload) -> { throw new AssertionError(); },
                    new V1AccountConnectionRegistry(), Runnable::run);
            try {
                channel.writeInbound(new TextWebSocketFrame(malformed));
                ((CloseWebSocketFrame) channel.readOutbound()).release();
                assertFalse(channel.isActive());
            } finally { channel.finishAndReleaseAll(); }
        }
        EmbeddedChannel saturated = channel(actor,
                (value, upload) -> ProfileImageMutationResult.Rejected.INVALID_IMAGE,
                new V1AccountConnectionRegistry(),
                task -> { throw new RejectedExecutionException(); });
        try {
            saturated.writeInbound(accountRequest());
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    @Test void codecAcceptsExactDecodedByteLimitAndRejectsOneByteMore() {
        var codec = new V1JsonProfileImageMutationCodec(Clock.fixed(NOW, ZoneOffset.UTC));
        byte[] maximum = new byte[LegacyV1AvatarUpload.MAX_BYTES];
        var accepted = codec.decode(uploadWire(maximum));
        try {
            assertEquals(V1JsonProfileImageMutationCodec.RequestKind.ACCOUNT, accepted.kind());
            assertEquals(LegacyV1AvatarUpload.MAX_BYTES, accepted.upload().byteSize());
        } finally { accepted.close(); }
        var rejected = codec.decode(uploadWire(
                new byte[LegacyV1AvatarUpload.MAX_BYTES + 1]));
        assertEquals(V1JsonProfileImageMutationCodec.RequestKind.MALFORMED, rejected.kind());
    }

    private static EmbeddedChannel channel(UUID actor, ProfileImageMutationUseCase useCase,
            V1AccountConnectionRegistry registry, java.util.concurrent.Executor executor) {
        EmbeddedChannel channel = new EmbeddedChannel(new V1ProfileImageMutationHandler(
                useCase, new V1JsonProfileImageMutationCodec(
                        Clock.fixed(NOW, ZoneOffset.UTC)), registry, executor,
                V1ProfileImageMutationEventSink.noop()));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor)); return channel;
    }

    private static ProfileImageMutationResult.Committed committed(boolean changed,
            Set<UUID> peers) {
        var metadata = new ProfileImageMetadataResult.Committed(
                "avatars/sha256/" + "00".repeat(32) + ".png", 2, changed, NOW,
                Optional.empty(), changed ? peers : Set.of());
        return new ProfileImageMutationResult.Committed(metadata, changed
                ? Optional.of(ProfileImageObjectPayload.copyOf(PNG)) : Optional.empty());
    }

    private static LegacyV1AuthenticatedIdentity identity(UUID actor) {
        return new LegacyV1AuthenticatedIdentity(1, actor, UUID.randomUUID(), UUID.randomUUID(),
                NOW.plusSeconds(60), "owner", "Owner", false);
    }
    private static TextWebSocketFrame accountRequest() {
        return new TextWebSocketFrame("{\"type\":\"AVATAR_UPLOAD_REQ\",\"data\":{"
                + "\"avatarData\":\"" + Base64.getEncoder().encodeToString(PNG) + "\"}}" );
    }
    private static byte[] uploadWire(byte[] bytes) {
        return ("{\"type\":\"AVATAR_UPLOAD_REQ\",\"data\":{\"avatarData\":\""
                + Base64.getEncoder().encodeToString(bytes) + "\"}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static TextWebSocketFrame roomRequest(long roomId) {
        return new TextWebSocketFrame("{\"type\":\"ROOM_AVATAR_UPLOAD_REQ\",\"data\":{"
                + "\"roomId\":" + roomId + ",\"avatarData\":\""
                + Base64.getEncoder().encodeToString(PNG) + "\"}}" );
    }
}
