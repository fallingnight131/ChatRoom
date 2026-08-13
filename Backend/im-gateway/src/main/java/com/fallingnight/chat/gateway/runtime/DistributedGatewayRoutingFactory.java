package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.application.messaging.ConversationEventRelayService;
import com.fallingnight.chat.application.routing.GatewayLiveEventConsumePort;
import com.fallingnight.chat.application.routing.GatewayLiveEventConsumerService;
import com.fallingnight.chat.application.routing.GatewayLiveEventPublishPort;
import com.fallingnight.chat.application.routing.GatewayRouteLeasePort;
import com.fallingnight.chat.application.routing.GatewayRouteRegistrationService;
import com.fallingnight.chat.application.routing.RoutedConversationEventPublisher;
import com.fallingnight.chat.gateway.operations.ConversationEventRelayBackoff;
import com.fallingnight.chat.gateway.operations.ConversationEventRelayLoop;
import com.fallingnight.chat.gateway.operations.ConversationEventRelayTelemetry;
import com.fallingnight.chat.gateway.operations.DistributedGatewayRoutingRuntime;
import com.fallingnight.chat.gateway.operations.GatewayLiveEventConsumerLoop;
import com.fallingnight.chat.gateway.operations.GatewayLiveEventConsumerTelemetry;
import com.fallingnight.chat.gateway.operations.GatewayRouteLeaseLoop;
import com.fallingnight.chat.gateway.transport.LocalConversationMessageHintRepairAdapter;
import com.fallingnight.chat.gateway.transport.DistributedConversationLiveRouter;
import com.fallingnight.chat.gateway.transport.SingleGatewayConversationLiveRouter;
import com.fallingnight.chat.persistence.postgres.PostgresConversationEventOutboxAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresConversationEventOutboxStatusAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresMessageAdapter;
import com.fallingnight.chat.routing.redis.LettuceGatewayRoutingAdapter;
import com.fallingnight.chat.routing.redis.RedisRoutingConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/** Builds the complete but still default-off PostgreSQL/Redis routing component graph. */
public final class DistributedGatewayRoutingFactory {
    private static final Duration ROUTE_LEASE = Duration.ofSeconds(30);
    private static final Duration ROUTE_RENEWAL = Duration.ofSeconds(10);
    private static final Duration OUTBOX_LEASE = Duration.ofSeconds(5);
    private static final int OUTBOX_BATCH_SIZE = 100;
    private static final int HINT_BATCH_SIZE = 100;
    private static final int MAXIMUM_TARGET_GATEWAYS = 64;
    private static final int MAXIMUM_STREAM_LENGTH = 1_000;
    private static final Duration HEALTHY_POLL = Duration.ofMillis(100);
    private static final Duration INITIAL_FAILURE_DELAY = Duration.ofMillis(100);
    private static final Duration MAXIMUM_FAILURE_DELAY = Duration.ofSeconds(30);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private DistributedGatewayRoutingFactory() { }

    public static Optional<DistributedGatewayRoutingComponents> create(
            DistributedGatewayRoutingConfig config, DataSource dataSource,
            SingleGatewayConversationLiveRouter localRouter, Clock clock) {
        return create(config, dataSource, localRouter, clock,
                redis -> {
                    LettuceGatewayRoutingAdapter adapter =
                            new LettuceGatewayRoutingAdapter(redis);
                    return new RoutingResources(adapter, adapter, adapter, adapter);
                }, DistributedGatewayRoutingFactory::scheduler, UUID::randomUUID);
    }

    static Optional<DistributedGatewayRoutingComponents> create(
            DistributedGatewayRoutingConfig config, DataSource dataSource,
            SingleGatewayConversationLiveRouter localRouter, Clock clock,
            RoutingResourcesFactory resourcesFactory, SchedulerFactory schedulerFactory,
            IdFactory ids) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(resourcesFactory, "resourcesFactory");
        Objects.requireNonNull(schedulerFactory, "schedulerFactory");
        Objects.requireNonNull(ids, "ids");
        if (!config.enabled()) return Optional.empty();
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(localRouter, "localRouter");
        Objects.requireNonNull(clock, "clock");

        RoutingResources resources = null;
        ScheduledExecutorService scheduler = null;
        try {
            RedisRoutingConfig redis = config.redis().orElseThrow();
            resources = Objects.requireNonNull(resourcesFactory.create(redis),
                    "routing resources");
            scheduler = Objects.requireNonNull(schedulerFactory.create(), "routing scheduler");
            UUID gatewayId = Objects.requireNonNull(ids.create(), "gatewayId");
            UUID relayOwner = Objects.requireNonNull(ids.create(), "relayOwner");

            GatewayRouteRegistrationService registration =
                    new GatewayRouteRegistrationService(
                            resources.routes(), gatewayId, ROUTE_LEASE, clock);
            DistributedConversationLiveRouter distributedRouter =
                    new DistributedConversationLiveRouter(localRouter, registration);
            GatewayRouteLeaseLoop leaseLoop = new GatewayRouteLeaseLoop(
                    () -> registration.renewGateway()
                            && distributedRouter.renewActiveRoutes(),
                    scheduler, clock, ROUTE_LEASE, ROUTE_RENEWAL,
                    INITIAL_FAILURE_DELAY, Duration.ofSeconds(5));

            PostgresMessageAdapter messages = new PostgresMessageAdapter(dataSource);
            GatewayLiveEventConsumerService consumer = new GatewayLiveEventConsumerService(
                    resources.consumer(),
                    new LocalConversationMessageHintRepairAdapter(localRouter, messages),
                    gatewayId, HINT_BATCH_SIZE);
            GatewayLiveEventConsumerTelemetry consumerTelemetry =
                    new GatewayLiveEventConsumerTelemetry();
            GatewayLiveEventConsumerLoop consumerLoop = new GatewayLiveEventConsumerLoop(
                    consumer, scheduler, consumerTelemetry, HINT_BATCH_SIZE, HEALTHY_POLL,
                    INITIAL_FAILURE_DELAY, MAXIMUM_FAILURE_DELAY);

            RoutedConversationEventPublisher publisher = new RoutedConversationEventPublisher(
                    resources.routes(), resources.publisher(), MAXIMUM_TARGET_GATEWAYS,
                    MAXIMUM_STREAM_LENGTH, clock);
            ConversationEventRelayService relay = new ConversationEventRelayService(
                    new PostgresConversationEventOutboxAdapter(dataSource), publisher,
                    relayOwner, OUTBOX_LEASE, OUTBOX_BATCH_SIZE,
                    INITIAL_FAILURE_DELAY, MAXIMUM_FAILURE_DELAY, clock);
            ConversationEventRelayTelemetry relayTelemetry =
                    new ConversationEventRelayTelemetry();
            ConversationEventRelayLoop relayLoop = new ConversationEventRelayLoop(
                    relay, scheduler,
                    new ConversationEventRelayBackoff(
                            HEALTHY_POLL, INITIAL_FAILURE_DELAY, MAXIMUM_FAILURE_DELAY),
                    relayTelemetry, OUTBOX_BATCH_SIZE);

            DistributedGatewayRoutingRuntime runtime =
                    new DistributedGatewayRoutingRuntime(relayLoop, leaseLoop, consumerLoop,
                            registration, scheduler, resources.closeable(), SHUTDOWN_TIMEOUT);
            resources = null;
            scheduler = null;
            return Optional.of(new DistributedGatewayRoutingComponents(
                    gatewayId, runtime, registration, distributedRouter,
                    new PostgresConversationEventOutboxStatusAdapter(dataSource),
                    relayTelemetry, consumerTelemetry));
        } catch (RuntimeException exception) {
            closeAfterFailure(scheduler, resources, exception);
            throw exception;
        }
    }

    private static ScheduledExecutorService scheduler() {
        AtomicInteger sequence = new AtomicInteger();
        ScheduledThreadPoolExecutor executor =
                (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(3, task -> {
                    Thread thread = new Thread(task,
                            "chat-distributed-routing-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static void closeAfterFailure(ScheduledExecutorService scheduler,
            RoutingResources resources, RuntimeException failure) {
        if (scheduler != null) scheduler.shutdownNow();
        if (resources == null) return;
        try {
            resources.closeable().close();
        } catch (Exception cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    record RoutingResources(GatewayRouteLeasePort routes,
            GatewayLiveEventPublishPort publisher, GatewayLiveEventConsumePort consumer,
            AutoCloseable closeable) {
        RoutingResources {
            Objects.requireNonNull(routes, "routes");
            Objects.requireNonNull(publisher, "publisher");
            Objects.requireNonNull(consumer, "consumer");
            Objects.requireNonNull(closeable, "closeable");
        }
    }

    @FunctionalInterface interface RoutingResourcesFactory {
        RoutingResources create(RedisRoutingConfig config);
    }
    @FunctionalInterface interface SchedulerFactory {
        ScheduledExecutorService create();
    }
    @FunctionalInterface interface IdFactory {
        UUID create();
    }
}
