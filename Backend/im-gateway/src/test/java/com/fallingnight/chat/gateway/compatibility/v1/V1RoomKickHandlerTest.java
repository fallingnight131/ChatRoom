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
import org.junit.jupiter.api.Test;

final class V1RoomKickHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test void bindsActorAndRoutesOnlyFirstCommittedEffects() {
        UUID actor = UUID.randomUUID(), target = UUID.randomUUID();
        UUID member = UUID.randomUUID(), conversation = UUID.randomUUID();
        var registry = new V1AccountConnectionRegistry();
        var targetChannel = new EmbeddedChannel(); var memberChannel = new EmbeddedChannel();
        registry.replace(target, targetChannel); registry.replace(member, memberChannel);
        var channel = channel(actor, command -> {
            assertEquals(actor, command.actorAccountId()); assertEquals(7, command.legacyRoomId());
            assertEquals("member", command.targetUsername());
            return new LegacyV1RoomKickResult.Kicked(conversation, 7, "Room", target,
                    "member", "Member", true, NOW);
        }, new LegacyV1RoomAudienceService((room, candidates) -> {
            assertEquals(conversation, room); return Set.of(member);
        }), registry, Runnable::run);
        try {
            channel.writeInbound(request()); channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            try { assertTrue(response.text().contains("\"type\":\"KICK_USER_RSP\""));
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"changed\":true"));
                assertFalse(response.text().contains(conversation.toString())); }
            finally { response.release(); }
            targetChannel.runPendingTasks(); assertFrame(targetChannel, "KICK_USER_NOTIFY");
            memberChannel.runPendingTasks(); assertFrame(memberChannel, "USER_LEFT");
            assertFrame(memberChannel, "SYSTEM_MSG");
        } finally {
            channel.finishAndReleaseAll(); targetChannel.finishAndReleaseAll();
            memberChannel.finishAndReleaseAll();
        }
    }

    @Test void duplicateAndRejectionDoNotNotifyAndMalformedOrSaturatedClose() {
        UUID actor = UUID.randomUUID(), target = UUID.randomUUID();
        UUID conversation = UUID.randomUUID(); var registry = new V1AccountConnectionRegistry();
        var targetChannel = new EmbeddedChannel(); registry.replace(target, targetChannel);
        var duplicate = channel(actor, command -> new LegacyV1RoomKickResult.Kicked(
                conversation, 7, "Room", target, "member", "Member", false, NOW),
                emptyAudience(), registry, Runnable::run);
        try { duplicate.writeInbound(request()); duplicate.runPendingTasks();
            TextWebSocketFrame response = duplicate.readOutbound();
            try { assertTrue(response.text().contains("\"changed\":false")); }
            finally { response.release(); }
            targetChannel.runPendingTasks(); assertNull(targetChannel.readOutbound());
        } finally { duplicate.finishAndReleaseAll(); targetChannel.finishAndReleaseAll(); }

        var rejected = channel(actor, command ->
                LegacyV1RoomKickResult.Rejected.TARGET_ROLE_PROTECTED,
                emptyAudience(), new V1AccountConnectionRegistry(), Runnable::run);
        try { rejected.writeInbound(request()); rejected.runPendingTasks();
            TextWebSocketFrame response = rejected.readOutbound();
            try { assertTrue(response.text().contains("TARGET_ROLE_PROTECTED")); }
            finally { response.release(); }
            assertTrue(rejected.isActive());
        } finally { rejected.finishAndReleaseAll(); }

        var malformed = channel(actor, command -> { throw new AssertionError(); },
                emptyAudience(), new V1AccountConnectionRegistry(), Runnable::run);
        try { malformed.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"KICK_USER_REQ\",\"data\":{\"roomId\":7}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        var saturated = channel(actor, command -> LegacyV1RoomKickResult.Rejected.INVALID_INPUT,
                emptyAudience(), new V1AccountConnectionRegistry(), task -> {
                    throw new RejectedExecutionException();
                });
        try { saturated.writeInbound(request());
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    @Test void codecRejectsUnknownDataAndNonIntegralRoom() {
        var codec = codec();
        assertEquals(V1JsonRoomKickCodec.RequestKind.MALFORMED, codec.decode(
                "{\"type\":\"KICK_USER_REQ\",\"data\":{\"roomId\":7,\"username\":\"m\",\"role\":\"MEMBER\"}}"
                        .getBytes(StandardCharsets.UTF_8)).kind());
        assertEquals(V1JsonRoomKickCodec.RequestKind.MALFORMED, codec.decode(
                "{\"type\":\"KICK_USER_REQ\",\"data\":{\"roomId\":1.5,\"username\":\"m\"}}"
                        .getBytes(StandardCharsets.UTF_8)).kind());
    }

    private static EmbeddedChannel channel(UUID actor, LegacyV1RoomKickUseCase useCase,
            LegacyV1RoomAudienceService audience, V1AccountConnectionRegistry registry,
            java.util.concurrent.Executor executor) {
        var channel = new EmbeddedChannel(new V1RoomKickHandler(useCase, audience, codec(),
                registry, executor, V1RoomKickEventSink.noop()));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(
                new LegacyV1AuthenticatedIdentity(1, actor, UUID.randomUUID(), UUID.randomUUID(),
                        NOW.plusSeconds(60), "owner", "Owner", false));
        registry.replace(actor, channel); return channel;
    }
    private static LegacyV1RoomAudienceService emptyAudience() {
        return new LegacyV1RoomAudienceService((room, candidates) -> Set.of());
    }
    private static V1JsonRoomKickCodec codec() {
        return new V1JsonRoomKickCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static TextWebSocketFrame request() { return new TextWebSocketFrame(
            "{\"type\":\"KICK_USER_REQ\",\"data\":{\"roomId\":7,\"username\":\"member\"}}"); }
    private static void assertFrame(EmbeddedChannel channel, String type) {
        TextWebSocketFrame frame = channel.readOutbound(); assertNotNull(frame);
        try { assertTrue(frame.text().contains("\"type\":\"" + type + "\"")); }
        finally { frame.release(); }
    }
}
