package com.fallingnight.chat.gateway.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.routing.ConversationGatewayRoute;
import com.fallingnight.chat.application.routing.ConversationGatewayRoutePage;
import com.fallingnight.chat.application.routing.GatewayLiveEventBatch;
import com.fallingnight.chat.application.routing.GatewayLiveEventConsumePort;
import com.fallingnight.chat.application.routing.GatewayLiveEventHint;
import com.fallingnight.chat.application.routing.GatewayLiveEventPublishPort;
import com.fallingnight.chat.application.routing.GatewayRouteLease;
import com.fallingnight.chat.application.routing.GatewayRouteLeasePort;
import com.fallingnight.chat.gateway.transport.SingleGatewayConversationLiveRouter;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

final class DistributedGatewayRoutingFactoryTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void disabledConfigCreatesNothingAndNeedsNoRuntimeDependencies() {
        AtomicBoolean resourcesCalled = new AtomicBoolean();
        var result = DistributedGatewayRoutingFactory.create(
                DistributedGatewayRoutingConfig.fromEnvironment(Map.of()),
                null, null, null,
                ignored -> { resourcesCalled.set(true); throw new AssertionError(); },
                () -> { throw new AssertionError(); }, UUID::randomUUID);

        assertTrue(result.isEmpty());
        assertFalse(resourcesCalled.get());
    }

    @Test
    void composesOneSharedAdapterAndClosesAllOwnedResources() {
        TrackingRoutes routes = new TrackingRoutes();
        TrackingScheduler scheduler = new TrackingScheduler();
        UUID gatewayId = UUID.randomUUID();
        UUID relayOwner = UUID.randomUUID();
        List<UUID> ids = List.of(gatewayId, relayOwner);
        AtomicInteger idIndex = new AtomicInteger();

        DistributedGatewayRoutingComponents components =
                DistributedGatewayRoutingFactory.create(enabled(), dataSource(),
                        new SingleGatewayConversationLiveRouter(CLOCK), CLOCK,
                        ignored -> new DistributedGatewayRoutingFactory.RoutingResources(
                                routes, routes, routes, routes),
                        () -> scheduler, () -> ids.get(idIndex.getAndIncrement()))
                        .orElseThrow();

        assertEquals(gatewayId, components.gatewayId());
        assertEquals(gatewayId, components.registration().gatewayId());
        assertFalse(components.runtime().readyForTraffic());
        components.runtime().close();
        assertTrue(routes.gatewayReleased);
        assertTrue(routes.closed);
        assertTrue(scheduler.shutdown);
    }

    @Test
    void closesAdapterWhenLaterConstructionFails() {
        TrackingRoutes routes = new TrackingRoutes();
        TrackingScheduler scheduler = new TrackingScheduler();

        NullPointerException failure = assertThrows(NullPointerException.class,
                () -> DistributedGatewayRoutingFactory.create(enabled(), dataSource(),
                        new SingleGatewayConversationLiveRouter(CLOCK), CLOCK,
                        ignored -> new DistributedGatewayRoutingFactory.RoutingResources(
                                routes, routes, routes, routes),
                        () -> scheduler, () -> null));

        assertEquals("gatewayId", failure.getMessage());
        assertTrue(routes.closed);
        assertTrue(scheduler.shutdown);
    }

    @Test
    void closesAdapterWhenSchedulerConstructionFailsAndPreservesCleanupFailure() {
        TrackingRoutes routes = new TrackingRoutes();
        routes.closeFailure = new IllegalStateException("close failed");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> DistributedGatewayRoutingFactory.create(enabled(), dataSource(),
                        new SingleGatewayConversationLiveRouter(CLOCK), CLOCK,
                        ignored -> new DistributedGatewayRoutingFactory.RoutingResources(
                                routes, routes, routes, routes),
                        () -> { throw new IllegalStateException("scheduler failed"); },
                        UUID::randomUUID));

        assertEquals("scheduler failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("close failed", failure.getSuppressed()[0].getMessage());
    }

    private static DistributedGatewayRoutingConfig enabled() {
        return DistributedGatewayRoutingConfig.fromEnvironment(Map.of(
                DistributedGatewayRoutingConfig.ENABLED, "true",
                DistributedGatewayRoutingConfig.REDIS_URI, "redis://127.0.0.1:6379/0",
                DistributedGatewayRoutingConfig.ALLOW_INSECURE_LOOPBACK, "true"));
    }

    private static DataSource dataSource() {
        return new DataSource() {
            @Override public Connection getConnection() { throw new AssertionError(); }
            @Override public Connection getConnection(String user, String password) {
                throw new AssertionError();
            }
            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException();
            }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("not a wrapper");
            }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    private static final class TrackingScheduler extends ScheduledThreadPoolExecutor {
        private boolean shutdown;
        private TrackingScheduler() { super(1); }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true; return super.shutdownNow();
        }
    }

    private static final class TrackingRoutes implements GatewayRouteLeasePort,
            GatewayLiveEventPublishPort, GatewayLiveEventConsumePort, AutoCloseable {
        private boolean gatewayReleased;
        private boolean closed;
        private RuntimeException closeFailure;

        @Override public boolean renewGateway(GatewayRouteLease lease) { return true; }
        @Override public boolean publishConversationRoute(ConversationGatewayRoute route) {
            return true;
        }
        @Override public ConversationGatewayRoutePage findConversationGateways(
                UUID conversationId, Instant observedAt, int limit) {
            return new ConversationGatewayRoutePage(List.of(), true);
        }
        @Override public boolean removeConversationRoute(UUID gatewayId, UUID conversationId) {
            return true;
        }
        @Override public boolean releaseGateway(UUID gatewayId) {
            gatewayReleased = true; return true;
        }
        @Override public PublishResult publish(
                GatewayLiveEventHint hint, int maximumStreamLength) {
            return PublishResult.PUBLISHED;
        }
        @Override public GatewayLiveEventBatch readAfter(
                UUID gatewayId, String afterStreamId, int limit) {
            return new GatewayLiveEventBatch(afterStreamId, List.of());
        }
        @Override public void close() {
            closed = true;
            if (closeFailure != null) throw closeFailure;
        }
    }
}
