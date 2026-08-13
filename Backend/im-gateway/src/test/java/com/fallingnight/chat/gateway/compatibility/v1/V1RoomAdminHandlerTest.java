package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class V1RoomAdminHandlerTest {
    @Test void bindsActorRoutesChangedTargetAndSuppressesUnchanged() {
        UUID actor = UUID.randomUUID(), target = UUID.randomUUID(), conversation = UUID.randomUUID();
        var registry = new V1AccountConnectionRegistry();
        var targetChannel = new EmbeddedChannel(); registry.replace(target, targetChannel);
        var sender = new EmbeddedChannel(new V1RoomAdminHandler(command -> {
            assertEquals(actor, command.actorAccountId()); assertEquals("member", command.targetUsername());
            return new LegacyV1RoomAdminResult.Changed(conversation, 7, target,
                    "member", "Member", true, true);
        }, codec(), registry, Runnable::run, V1RoomAdminEventSink.noop()));
        sender.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
        try {
            sender.writeInbound(request(true)); sender.runPendingTasks();
            TextWebSocketFrame response = sender.readOutbound();
            try { assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"changed\":true")); }
            finally { response.release(); }
            targetChannel.runPendingTasks(); TextWebSocketFrame status = targetChannel.readOutbound();
            try { assertTrue(status.text().contains("\"type\":\"ADMIN_STATUS\"")); }
            finally { status.release(); }
        } finally { sender.finishAndReleaseAll(); targetChannel.finishAndReleaseAll(); }
    }

    @Test void rejectionStaysOpenAndMalformedCloses() {
        UUID actor = UUID.randomUUID(); var registry = new V1AccountConnectionRegistry();
        var rejected = new EmbeddedChannel(new V1RoomAdminHandler(command ->
                LegacyV1RoomAdminResult.Rejected.OWNER_PROTECTED,
                codec(), registry, Runnable::run, V1RoomAdminEventSink.noop()));
        rejected.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
        try { rejected.writeInbound(request(false)); rejected.runPendingTasks();
            TextWebSocketFrame response = rejected.readOutbound();
            try { assertTrue(response.text().contains("OWNER_PROTECTED")); }
            finally { response.release(); }
            assertTrue(rejected.isActive());
        } finally { rejected.finishAndReleaseAll(); }
        var malformed = new EmbeddedChannel(new V1RoomAdminHandler(command -> { throw new AssertionError(); },
                codec(), registry, Runnable::run, V1RoomAdminEventSink.noop()));
        malformed.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
        try { malformed.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"SET_ADMIN_REQ\",\"data\":{\"roomId\":7,\"username\":\"x\"}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release(); assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }
    }

    @Test void unchangedSuccessDoesNotNotifyTarget() {
        UUID actor = UUID.randomUUID(), target = UUID.randomUUID();
        var registry = new V1AccountConnectionRegistry();
        var targetChannel = new EmbeddedChannel(); registry.replace(target, targetChannel);
        var sender = new EmbeddedChannel(new V1RoomAdminHandler(command ->
                new LegacyV1RoomAdminResult.Changed(UUID.randomUUID(), 7, target,
                        "member", "Member", true, false), codec(), registry,
                Runnable::run, V1RoomAdminEventSink.noop()));
        sender.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
        try {
            sender.writeInbound(request(true)); sender.runPendingTasks();
            ((TextWebSocketFrame) sender.readOutbound()).release();
            targetChannel.runPendingTasks(); assertNull(targetChannel.readOutbound());
        } finally { sender.finishAndReleaseAll(); targetChannel.finishAndReleaseAll(); }
    }
    private static V1JsonRoomAdminCodec codec() { return new V1JsonRoomAdminCodec(
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)); }
    private static TextWebSocketFrame request(boolean admin) { return new TextWebSocketFrame(
            "{\"type\":\"SET_ADMIN_REQ\",\"data\":{\"roomId\":7,\"username\":\"member\",\"isAdmin\":" + admin + "}}"); }
    private static LegacyV1AuthenticatedIdentity identity(UUID actor) { return new LegacyV1AuthenticatedIdentity(
            1, actor, UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(60),
            "owner", "Owner", false); }
}
