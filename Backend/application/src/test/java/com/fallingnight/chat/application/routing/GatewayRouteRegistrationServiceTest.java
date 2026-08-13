package com.fallingnight.chat.application.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GatewayRouteRegistrationServiceTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test void renewsBootLeaseThenRegistersAndRepairsVisibleWindow() {
        StubRoutes routes = new StubRoutes();
        var service = service(routes);
        assertTrue(service.renewGateway());
        assertEquals(NOW.plusSeconds(30), routes.lease.expiresAt());

        UUID conversation = UUID.randomUUID();
        Optional<ConversationRouteRegistration> result = service.registerAfterCatchUp(
                conversation, 10, (id, after) -> {
                    assertEquals(conversation, id); assertEquals(10, after); return 12;
                });
        assertEquals(12, result.orElseThrow().caughtUpThroughSequence());
        assertEquals(10, routes.route.caughtUpThroughSequence());
        assertFalse(routes.removed);
    }

    @Test void removesVisibleRouteWhenSecondRepairFailsOrMovesBackwards() {
        StubRoutes routes = new StubRoutes();
        var service = service(routes);
        assertThrows(IllegalStateException.class, () -> service.registerAfterCatchUp(
                UUID.randomUUID(), 10, (id, after) -> 9));
        assertTrue(routes.removed);

        routes.removed = false;
        assertThrows(IllegalStateException.class, () -> service.registerAfterCatchUp(
                UUID.randomUUID(), 10, (id, after) -> { throw new IllegalStateException(); }));
        assertTrue(routes.removed);
    }

    @Test void doesNotRepairWhenRoutePublicationIsRejected() {
        StubRoutes routes = new StubRoutes(); routes.publish = false;
        assertTrue(service(routes).registerAfterCatchUp(UUID.randomUUID(), 2,
                (id, after) -> { throw new AssertionError(); }).isEmpty());
    }

    private static GatewayRouteRegistrationService service(StubRoutes routes) {
        return new GatewayRouteRegistrationService(routes, UUID.randomUUID(),
                Duration.ofSeconds(30), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class StubRoutes implements GatewayRouteLeasePort {
        private GatewayRouteLease lease;
        private ConversationGatewayRoute route;
        private boolean publish = true;
        private boolean removed;
        @Override public boolean renewGateway(GatewayRouteLease value) { lease = value; return true; }
        @Override public boolean publishConversationRoute(ConversationGatewayRoute value) {
            route = value; return publish;
        }
        @Override public ConversationGatewayRoutePage findConversationGateways(
                UUID id, Instant at, int limit) { return new ConversationGatewayRoutePage(java.util.List.of(), true); }
        @Override public boolean removeConversationRoute(UUID gateway, UUID conversation) {
            removed = true; return true;
        }
        @Override public boolean releaseGateway(UUID gateway) { return true; }
    }
}
