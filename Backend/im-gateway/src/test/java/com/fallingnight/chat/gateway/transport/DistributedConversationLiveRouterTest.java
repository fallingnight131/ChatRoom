package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.messaging.MessageHistoryQuery;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import com.fallingnight.chat.application.routing.ConversationGatewayRoute;
import com.fallingnight.chat.application.routing.ConversationGatewayRoutePage;
import com.fallingnight.chat.application.routing.GatewayRouteLease;
import com.fallingnight.chat.application.routing.GatewayRouteLeasePort;
import com.fallingnight.chat.application.routing.GatewayRouteRegistrationService;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.MessageRecord;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DistributedConversationLiveRouterTest {
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID DEVICE = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void activatesRouteThenRepairsMissedMessageFromServerTruth() throws Exception {
        Routes routes = new Routes();
        SingleGatewayConversationLiveRouter local =
                new SingleGatewayConversationLiveRouter(CLOCK);
        DistributedConversationLiveRouter router = router(local, routes);
        EmbeddedChannel channel = authenticated();
        AtomicInteger reads = new AtomicInteger();
        var history = (com.fallingnight.chat.application.messaging.MessageHistoryPort) query ->
                reads.getAndIncrement() == 0
                        ? new MessageHistoryResult.Page(List.of(), 0, 0, false)
                        : new MessageHistoryResult.Page(List.of(message(1)), 1, 1, false);
        MessageHistoryQuery query = new MessageHistoryQuery(CONVERSATION, ACCOUNT, 0, 100);
        try {
            router.readAndSubscribe(channel, query, history);
            assertNull(channel.readOutbound());
            router.activateSubscription(channel, query, history);

            assertEquals(1, routes.published);
            assertEquals(2, reads.get());
            Envelope event = channel.readOutbound();
            assertEquals(1, MessageRecord.parseFrom(event.getPayload())
                    .getConversationSequence());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectedRouteRollsBackLocalSubscription() {
        Routes routes = new Routes(); routes.acceptPublish = false;
        SingleGatewayConversationLiveRouter local =
                new SingleGatewayConversationLiveRouter(CLOCK);
        DistributedConversationLiveRouter router = router(local, routes);
        EmbeddedChannel channel = authenticated();
        MessageHistoryQuery query = new MessageHistoryQuery(CONVERSATION, ACCOUNT, 0, 100);
        var history = (com.fallingnight.chat.application.messaging.MessageHistoryPort) ignored ->
                new MessageHistoryResult.Page(List.of(), 0, 0, false);
        try {
            router.readAndSubscribe(channel, query, history);
            assertThrows(IllegalStateException.class,
                    () -> router.activateSubscription(channel, query, history));
            assertEquals(0, local.activeConversationCount());
            assertEquals(0, router.publish(message(1)).published());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void removesExternalRouteOnlyAfterLastLocalSubscriberLeaves() {
        Routes routes = new Routes();
        SingleGatewayConversationLiveRouter local =
                new SingleGatewayConversationLiveRouter(CLOCK);
        DistributedConversationLiveRouter router = router(local, routes);
        EmbeddedChannel first = authenticated(), second = authenticated();
        MessageHistoryQuery query = new MessageHistoryQuery(CONVERSATION, ACCOUNT, 0, 100);
        var history = (com.fallingnight.chat.application.messaging.MessageHistoryPort) ignored ->
                new MessageHistoryResult.Page(List.of(), 0, 0, false);
        try {
            router.readAndSubscribe(first, query, history);
            router.activateSubscription(first, query, history);
            router.readAndSubscribe(second, query, history);
            router.activateSubscription(second, query, history);
            router.unsubscribe(first);
            assertEquals(0, routes.removed);
            router.unsubscribe(second);
            assertEquals(1, routes.removed);
        } finally {
            first.finishAndReleaseAll(); second.finishAndReleaseAll();
        }
    }

    private static DistributedConversationLiveRouter router(
            SingleGatewayConversationLiveRouter local, Routes routes) {
        return new DistributedConversationLiveRouter(local,
                new GatewayRouteRegistrationService(
                        routes, UUID.randomUUID(), Duration.ofSeconds(30), CLOCK));
    }

    private static EmbeddedChannel authenticated() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT, DEVICE, UUID.randomUUID()));
        return channel;
    }

    private static StoredMessage message(long sequence) {
        return new StoredMessage(UUID.randomUUID(), CONVERSATION, sequence,
                ACCOUNT, DEVICE, "message-" + sequence, 1,
                "body".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                CLOCK.instant());
    }

    private static final class Routes implements GatewayRouteLeasePort {
        private boolean acceptPublish = true;
        private int published;
        private int removed;
        @Override public boolean renewGateway(GatewayRouteLease lease) { return true; }
        @Override public boolean publishConversationRoute(ConversationGatewayRoute route) {
            published++; return acceptPublish;
        }
        @Override public ConversationGatewayRoutePage findConversationGateways(
                UUID conversationId, Instant observedAt, int limit) {
            return new ConversationGatewayRoutePage(List.of(), true);
        }
        @Override public boolean removeConversationRoute(UUID gatewayId, UUID conversationId) {
            removed++; return true;
        }
        @Override public boolean releaseGateway(UUID gatewayId) { return true; }
    }
}
