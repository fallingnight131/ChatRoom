package com.fallingnight.chat.application.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fallingnight.chat.application.messaging.ConversationEventOutboxClaim;
import com.fallingnight.chat.application.messaging.ConversationEventPublicationOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class RoutedConversationEventPublisherTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void publishesStablePayloadFreeHintToEveryCompleteLeasedTarget() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        StubRoutes routes = new StubRoutes(new ConversationGatewayRoutePage(
                List.of(first, second), true));
        List<GatewayLiveEventHint> hints = new ArrayList<>();
        var publisher = publisher(routes, (hint, limit) -> {
            assertEquals(1_000, limit);
            hints.add(hint);
            return GatewayLiveEventPublishPort.PublishResult.PUBLISHED;
        });

        assertEquals(ConversationEventPublicationOutcome.PUBLISHED,
                publisher.publish(claim()));
        assertEquals(List.of(first, second), hints.stream()
                .map(GatewayLiveEventHint::targetGatewayId).toList());
        assertEquals(1, hints.stream().map(GatewayLiveEventHint::eventId).distinct().count());
        assertEquals(1, hints.stream().map(GatewayLiveEventHint::conversationId)
                .distinct().count());
    }

    @Test
    void refusesPartialTargetsAndRetriesAnyTargetFailure() {
        var incomplete = publisher(new StubRoutes(
                new ConversationGatewayRoutePage(List.of(UUID.randomUUID()), false)),
                (hint, limit) -> { throw new AssertionError("partial targets must not publish"); });
        assertEquals(ConversationEventPublicationOutcome.DEPENDENCY_REJECTED,
                incomplete.publish(claim()));

        var unavailable = publisher(new StubRoutes(new ConversationGatewayRoutePage(
                List.of(UUID.randomUUID(), UUID.randomUUID()), true)),
                (hint, limit) -> GatewayLiveEventPublishPort.PublishResult.DEPENDENCY_UNAVAILABLE);
        assertEquals(ConversationEventPublicationOutcome.DEPENDENCY_UNAVAILABLE,
                unavailable.publish(claim()));
    }

    private static RoutedConversationEventPublisher publisher(
            GatewayRouteLeasePort routes, GatewayLiveEventPublishPort events) {
        return new RoutedConversationEventPublisher(routes, events, 8, 1_000,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ConversationEventOutboxClaim claim() {
        return new ConversationEventOutboxClaim(UUID.randomUUID(), UUID.randomUUID(), 7,
                UUID.randomUUID(), UUID.randomUUID(), NOW, NOW.plusSeconds(5), 1);
    }

    private static final class StubRoutes implements GatewayRouteLeasePort {
        private final ConversationGatewayRoutePage page;

        private StubRoutes(ConversationGatewayRoutePage page) {
            this.page = page;
        }

        @Override public boolean renewGateway(GatewayRouteLease lease) { return false; }
        @Override public boolean publishConversationRoute(ConversationGatewayRoute route) {
            return false;
        }
        @Override public ConversationGatewayRoutePage findConversationGateways(
                UUID conversationId, Instant observedAt, int limit) {
            assertEquals(NOW, observedAt);
            assertEquals(8, limit);
            return page;
        }
        @Override public boolean removeConversationRoute(UUID gatewayId, UUID conversationId) {
            return false;
        }
        @Override public boolean releaseGateway(UUID gatewayId) { return false; }
    }
}
