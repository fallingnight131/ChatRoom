package com.fallingnight.chat.gateway.compatibility.v1;
import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.compatibility.v1.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
final class V1RoomMessageHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    @Test void bindsActorAndFiltersAudience() {
        UUID actor = UUID.randomUUID(), member = UUID.randomUUID(), outsider = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        V1AccountConnectionRegistry registry = new V1AccountConnectionRegistry();
        EmbeddedChannel memberChannel = new EmbeddedChannel(), outsiderChannel = new EmbeddedChannel();
        registry.replace(member, memberChannel); registry.replace(outsider, outsiderChannel);
        EmbeddedChannel sender = new EmbeddedChannel(new V1RoomMessageHandler(command -> {
            assertEquals(actor, command.senderAccountId()); assertEquals(7, command.legacyRoomId());
            return new LegacyV1RoomMessageResult.Accepted(false, 7, 101, 8, NOW, conversation);
        }, new LegacyV1RoomAudienceService((actual, candidates) -> {
            assertEquals(conversation, actual); return Set.of(member);
        }), new V1JsonRoomMessageCodec(Clock.fixed(NOW, ZoneOffset.UTC)), registry,
                Runnable::run, V1RoomMessageEventSink.noop()));
        sender.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(actor));
        try {
            sender.writeInbound(request()); sender.runPendingTasks();
            ((TextWebSocketFrame) sender.readOutbound()).release();
            TextWebSocketFrame echo = sender.readOutbound();
            try { assertTrue(echo.text().contains("\"sender\":\"owner\"")); }
            finally { echo.release(); }
            memberChannel.runPendingTasks(); ((TextWebSocketFrame) memberChannel.readOutbound()).release();
            outsiderChannel.runPendingTasks(); assertNull(outsiderChannel.readOutbound());
        } finally { sender.finishAndReleaseAll(); memberChannel.finishAndReleaseAll();
            outsiderChannel.finishAndReleaseAll(); }
    }
    @Test void malformedCloses() {
        EmbeddedChannel channel = new EmbeddedChannel(new V1RoomMessageHandler(command ->
                LegacyV1RoomMessageResult.Rejected.INVALID_MESSAGE,
                new LegacyV1RoomAudienceService((id, candidates) -> Set.of()),
                new V1JsonRoomMessageCodec(Clock.fixed(NOW, ZoneOffset.UTC)),
                new V1AccountConnectionRegistry(), Runnable::run, V1RoomMessageEventSink.noop()));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(identity(UUID.randomUUID()));
        try {
            channel.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"CHAT_MSG\",\"data\":{\"roomId\":1.5}}"));
            ((CloseWebSocketFrame) channel.readOutbound()).release(); assertFalse(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }
    private static LegacyV1AuthenticatedIdentity identity(UUID actor) {
        return new LegacyV1AuthenticatedIdentity(1, actor, UUID.randomUUID(), UUID.randomUUID(),
                NOW.plusSeconds(60), "owner", "Owner", false);
    }
    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame("{\"type\":\"CHAT_MSG\",\"id\":\"envelope\",\"data\":{"
                + "\"roomId\":7,\"sender\":\"spoofed\",\"content\":\"hello\","
                + "\"contentType\":\"text\",\"clientMessageId\":\"room-client\"}}");
    }
}
