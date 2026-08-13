package com.fallingnight.chat.routing.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fallingnight.chat.application.routing.*;
import io.lettuce.core.RedisClient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class LettuceGatewayRoutingAdapterTest {
    private static final String URI = System.getenv("CHATROOM_TEST_REDIS_URI");

    @Test void expiresRoutesBoundsStreamsAndSurvivesReconnect() throws Exception {
        assumeTrue(URI != null && !URI.isBlank(), "set CHATROOM_TEST_REDIS_URI");
        RedisClient cleanup = RedisClient.create(URI);
        try (var connection = cleanup.connect()) { connection.sync().flushdb(); }
        finally { cleanup.shutdown(); }

        var config = new RedisRoutingConfig(URI, Duration.ofSeconds(1), 64, true);
        UUID gateway = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        Instant now = Instant.now();
        try (var adapter = new LettuceGatewayRoutingAdapter(config)) {
            assertFalse(adapter.publishConversationRoute(new ConversationGatewayRoute(
                    gateway, conversation, 0, now, now.plusSeconds(2))));
            assertTrue(adapter.renewGateway(
                    new GatewayRouteLease(gateway, now, now.plusSeconds(2))));
            assertTrue(adapter.publishConversationRoute(new ConversationGatewayRoute(
                    gateway, conversation, 7, now, now.plusSeconds(2))));
            assertEquals(java.util.List.of(gateway), adapter.findConversationGateways(
                    conversation, now, 8).gatewayIds());

            for (int sequence = 1; sequence <= 150; sequence++) {
                assertEquals(GatewayLiveEventPublishPort.PublishResult.PUBLISHED,
                        adapter.publish(new GatewayLiveEventHint(gateway, UUID.randomUUID(),
                                conversation, sequence), 100));
            }
            GatewayLiveEventBatch firstBatch = adapter.readAfter(gateway, "0-0", 60);
            assertEquals(60, firstBatch.entries().size());
            assertEquals(51, firstBatch.entries().getFirst().hint().conversationSequence());
            assertEquals(110, firstBatch.entries().getLast().hint().conversationSequence());
            GatewayLiveEventBatch secondBatch = adapter.readAfter(
                    gateway, firstBatch.nextStreamId(), 60);
            assertEquals(40, secondBatch.entries().size());
            assertEquals(150, secondBatch.entries().getLast().hint().conversationSequence());
            assertTrue(adapter.readAfter(gateway, secondBatch.nextStreamId(), 60)
                    .entries().isEmpty());
        }

        RedisClient inspect = RedisClient.create(URI);
        try (var connection = inspect.connect()) {
            assertEquals(100, connection.sync().xlen(
                    "chat:v2:gateway:" + gateway + ":events"));
        } finally { inspect.shutdown(); }

        Thread.sleep(2_100);
        try (var restarted = new LettuceGatewayRoutingAdapter(config)) {
            assertTrue(restarted.findConversationGateways(
                    conversation, Instant.now(), 8).gatewayIds().isEmpty());
            assertFalse(restarted.releaseGateway(gateway));
        }
    }
}
