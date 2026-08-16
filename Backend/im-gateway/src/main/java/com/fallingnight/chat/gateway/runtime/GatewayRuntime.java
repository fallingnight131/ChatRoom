package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.application.contact.AccountBlockService;
import com.fallingnight.chat.application.identity.AuthenticationService;
import com.fallingnight.chat.application.identity.SessionResumeService;
import com.fallingnight.chat.application.identity.DeviceManagementService;
import com.fallingnight.chat.gateway.operations.AttachmentCleanupTelemetry;
import com.fallingnight.chat.gateway.operations.GatewayAdminServer;
import com.fallingnight.chat.gateway.operations.GatewayProcessResources;
import com.fallingnight.chat.gateway.operations.ResidentMemorySampler;
import com.fallingnight.chat.gateway.operations.PrometheusConversationEventOutboxMetrics;
import com.fallingnight.chat.gateway.operations.PrometheusGatewayRoutingMetrics;
import com.fallingnight.chat.gateway.transport.AuthenticationTelemetry;
import com.fallingnight.chat.gateway.transport.AuthenticationWorkerPool;
import com.fallingnight.chat.gateway.transport.InMemoryAuthenticationAdmissionControl;
import com.fallingnight.chat.gateway.transport.InMemoryMessageForwardAdmissionPort;
import com.fallingnight.chat.gateway.transport.MessagingWorkerPool;
import com.fallingnight.chat.gateway.transport.MessagingTelemetry;
import com.fallingnight.chat.gateway.transport.DeviceConnectionRegistry;
import com.fallingnight.chat.gateway.transport.DeviceManagementTelemetry;
import com.fallingnight.chat.gateway.transport.SingleGatewayConversationLiveRouter;
import com.fallingnight.chat.gateway.transport.ConversationLiveRouter;
import com.fallingnight.chat.identity.crypto.Argon2idCredentialHasher;
import com.fallingnight.chat.identity.crypto.CompatibleCredentialVerifier;
import com.fallingnight.chat.persistence.postgres.PostgresIdentityAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresConversationDirectoryAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresConversationParticipantAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresMessageAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresMessageReactionAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresMessagePinAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresMessageEditAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresMessageForwardAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresMessageSearchAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresAccountBlockAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresDeviceManagementAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresMigrator;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Owns the validated database, workers, admin endpoint, and product listener lifecycle. */
public final class GatewayRuntime implements AutoCloseable {
    private static final Duration WORKER_CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final System.Logger LOGGER = System.getLogger(GatewayRuntime.class.getName());

    private final AtomicBoolean readiness;
    private final BooleanSupplier dependencyReadiness;
    private final ManagedServer admin;
    private final BlockingServer product;
    private final AutoCloseable authenticationWorkers;
    private final AutoCloseable messagingWorkers;
    private final AutoCloseable dataSource;
    private final ManagedDependency distributedRouting;
    private final AutoCloseable residentMemorySampler;
    private final Duration drainTimeout;
    private boolean started;
    private boolean productStarted;
    private boolean closed;

    private GatewayRuntime(
            AtomicBoolean readiness,
            ManagedServer admin,
            BlockingServer product,
            AutoCloseable authenticationWorkers,
            AutoCloseable messagingWorkers,
            AutoCloseable dataSource,
            ManagedDependency distributedRouting,
            AutoCloseable residentMemorySampler,
            BooleanSupplier dependencyReadiness,
            Duration drainTimeout) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.product = Objects.requireNonNull(product, "product");
        this.authenticationWorkers = Objects.requireNonNull(
                authenticationWorkers, "authenticationWorkers");
        this.messagingWorkers = Objects.requireNonNull(messagingWorkers, "messagingWorkers");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.distributedRouting = Objects.requireNonNull(distributedRouting, "distributedRouting");
        this.residentMemorySampler = Objects.requireNonNull(
                residentMemorySampler, "residentMemorySampler");
        this.dependencyReadiness = Objects.requireNonNull(
                dependencyReadiness, "dependencyReadiness");
        this.drainTimeout = Objects.requireNonNull(drainTimeout, "drainTimeout");
    }

    public static GatewayRuntime create(GatewayRuntimeConfig config) {
        Objects.requireNonNull(config, "config");
        new PostgresMigrator(
                        config.postgresUrl(),
                        config.postgresUser(),
                        config.postgresPassword())
                .validate();

        HikariDataSource dataSource = null;
        AuthenticationWorkerPool workers = null;
        MessagingWorkerPool messagingWorkers = null;
        GatewayAdminServer adminServer = null;
        V2GatewayServer productServer = null;
        ManagedDependency distributedRouting = null;
        ResidentMemorySampler residentMemorySampler = null;
        try {
            dataSource = GatewayPostgresDataSource.create(config);
            PostgresIdentityAdapter identity = new PostgresIdentityAdapter(dataSource);
            PostgresMessageAdapter messages = new PostgresMessageAdapter(dataSource);
            PostgresMessageReactionAdapter reactions =
                    new PostgresMessageReactionAdapter(dataSource);
            PostgresMessagePinAdapter pins = new PostgresMessagePinAdapter(dataSource);
            PostgresMessageEditAdapter edits = new PostgresMessageEditAdapter(dataSource);
            PostgresMessageForwardAdapter durableForwards =
                    new PostgresMessageForwardAdapter(dataSource);
            PostgresMessageSearchAdapter search = new PostgresMessageSearchAdapter(dataSource);
            AccountBlockService accountBlocks =
                    new AccountBlockService(new PostgresAccountBlockAdapter(dataSource));
            PostgresConversationDirectoryAdapter conversations =
                    new PostgresConversationDirectoryAdapter(dataSource);
            PostgresConversationParticipantAdapter participants =
                    new PostgresConversationParticipantAdapter(dataSource);
            DeviceManagementService deviceManagement = new DeviceManagementService(
                    new PostgresDeviceManagementAdapter(dataSource));
            Clock clock = Clock.systemUTC();
            AuthenticationService authentication = new AuthenticationService(
                    identity,
                    new CompatibleCredentialVerifier(),
                    identity,
                    new Argon2idCredentialHasher(),
                    identity,
                    clock);
            SessionResumeService sessionResume = new SessionResumeService(identity, clock);
            AuthenticationTelemetry telemetry = new AuthenticationTelemetry();
            MessagingTelemetry messagingTelemetry = new MessagingTelemetry();
            DeviceManagementTelemetry deviceTelemetry = new DeviceManagementTelemetry();
            DeviceConnectionRegistry deviceConnections = new DeviceConnectionRegistry();
            SingleGatewayConversationLiveRouter liveRouter =
                    new SingleGatewayConversationLiveRouter(clock);
            var distributedComponents = DistributedGatewayRoutingFactory.create(
                    config.distributedRouting(), dataSource, liveRouter, clock);
            distributedRouting = distributedComponents
                    .<ManagedDependency>map(components -> managed(components.runtime()))
                    .orElseGet(GatewayRuntime::disabledDependency);
            ConversationLiveRouter productLiveRouter = distributedComponents
                    .<ConversationLiveRouter>map(DistributedGatewayRoutingComponents::liveRouter)
                    .orElse(liveRouter);
            Supplier<String> distributedMetrics = distributedComponents
                    .<Supplier<String>>map(components -> () -> renderDistributedMetrics(
                            components, clock)).orElse(() -> "");
            AttachmentCleanupTelemetry attachmentCleanupTelemetry =
                    new AttachmentCleanupTelemetry();
            InMemoryAuthenticationAdmissionControl admission =
                    new InMemoryAuthenticationAdmissionControl(config.admissionLimits(), clock);
            InMemoryMessageForwardAdmissionPort forwards =
                    new InMemoryMessageForwardAdmissionPort(
                            durableForwards, config.forwardAdmissionLimits(), clock);
            AtomicBoolean readiness = new AtomicBoolean();
            HikariDataSource readinessDataSource = dataSource;
            ManagedDependency routingReadiness = distributedRouting;
            BooleanSupplier dependencyReadiness =
                    () -> GatewayPostgresDataSource.isReady(readinessDataSource)
                            && routingReadiness.ready();
            BooleanSupplier publicReadiness =
                    () -> readiness.get() && dependencyReadiness.getAsBoolean();
            workers = new AuthenticationWorkerPool(
                    config.authenticationWorkers(),
                    config.authenticationQueueCapacity(),
                    WORKER_CLOSE_TIMEOUT);
            messagingWorkers = new MessagingWorkerPool(
                    config.messagingWorkers(),
                    config.messagingQueueCapacity(),
                    WORKER_CLOSE_TIMEOUT);
            productServer = new V2GatewayServer(
                    config,
                    authentication,
                    sessionResume,
                    messages,
                    messages,
                    conversations,
                    participants,
                    reactions,
                    pins,
                    edits,
                    forwards,
                    search,
                    accountBlocks,
                    deviceManagement,
                    workers,
                    messagingWorkers,
                    admission,
                    telemetry,
                    messagingTelemetry,
                    deviceTelemetry,
                    deviceConnections,
                    productLiveRouter,
                    publicReadiness);
            V2GatewayServer eventLoopMetricsServer = productServer;
            residentMemorySampler = ResidentMemorySampler.startDefault(Duration.ofMillis(250));
            ResidentMemorySampler residentMemoryMetricsSampler = residentMemorySampler;
            adminServer = new GatewayAdminServer(
                    config.adminAddress(),
                    config.adminWorkers(),
                    telemetry,
                    messagingTelemetry,
                    deviceTelemetry,
                    attachmentCleanupTelemetry,
                    workers::activeCount,
                    workers::queuedCount,
                    messagingWorkers::activeCount,
                    messagingWorkers::queuedCount,
                    () -> GatewayPostgresDataSource.snapshot(
                            readinessDataSource, config.postgresPoolMaximum()),
                    eventLoopMetricsServer::eventLoopSnapshot,
                    GatewayProcessResources::snapshot,
                    residentMemoryMetricsSampler::snapshot,
                    publicReadiness,
                    distributedMetrics,
                    config.releaseIdentity());
            return new GatewayRuntime(
                    readiness,
                    managed(adminServer),
                    blocking(productServer),
                    workers,
                    messagingWorkers,
                    dataSource,
                    distributedRouting,
                    residentMemorySampler,
                    dependencyReadiness,
                    config.drainTimeout());
        } catch (RuntimeException exception) {
            closeQuietly(adminServer);
            closeQuietly(productServer);
            closeQuietly(distributedRouting);
            closeQuietly(residentMemorySampler);
            closeQuietly(messagingWorkers);
            closeQuietly(workers);
            closeQuietly(dataSource);
            throw exception;
        }
    }

    static GatewayRuntime forTest(
            AtomicBoolean readiness,
            ManagedServer admin,
            BlockingServer product,
            AutoCloseable authenticationWorkers,
            AutoCloseable messagingWorkers,
            AutoCloseable dataSource) {
        return new GatewayRuntime(
                readiness, admin, product, authenticationWorkers, messagingWorkers, dataSource,
                disabledDependency(), () -> { }, () -> true, Duration.ZERO);
    }

    static GatewayRuntime forTest(
            AtomicBoolean readiness, ManagedServer admin, BlockingServer product,
            AutoCloseable authenticationWorkers, AutoCloseable messagingWorkers,
            AutoCloseable dataSource, ManagedDependency distributedRouting) {
        return new GatewayRuntime(readiness, admin, product, authenticationWorkers,
                messagingWorkers, dataSource, distributedRouting,
                () -> { }, distributedRouting::ready, Duration.ZERO);
    }

    public synchronized void start() {
        if (started || closed) {
            throw new IllegalStateException("gateway runtime cannot be started");
        }
        started = true;
        try {
            admin.start();
            distributedRouting.start();
            product.start();
            productStarted = true;
            readiness.set(true);
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    public void awaitTermination() {
        synchronized (this) {
            if (!started || closed) {
                throw new IllegalStateException("gateway runtime is not running");
            }
        }
        product.awaitClose();
    }

    public boolean isReady() {
        return readiness.get() && dependencyReadiness.getAsBoolean();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        readiness.set(false);
        if (productStarted) drainProduct();
        closeQuietly(product);
        closeQuietly(distributedRouting);
        closeQuietly(admin);
        closeQuietly(residentMemorySampler);
        closeQuietly(messagingWorkers);
        closeQuietly(authenticationWorkers);
        closeQuietly(dataSource);
    }

    private void drainProduct() {
        try {
            product.stopAccepting();
            boolean drained = product.awaitDrained(drainTimeout);
            LOGGER.log(
                    drained ? System.Logger.Level.INFO : System.Logger.Level.WARNING,
                    drained ? "event=gateway_drain_complete" : "event=gateway_drain_timeout");
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "event=gateway_drain_failed type="
                            + exception.getClass().getSimpleName());
        }
    }

    private static ManagedServer managed(GatewayAdminServer server) {
        return new ManagedServer() {
            @Override
            public void start() {
                server.start();
            }

            @Override
            public void close() {
                server.close();
            }
        };
    }

    private static ManagedDependency managed(
            com.fallingnight.chat.gateway.operations.DistributedGatewayRoutingRuntime runtime) {
        return new ManagedDependency() {
            @Override public void start() { runtime.start(); }
            @Override public boolean ready() { return runtime.readyForTraffic(); }
            @Override public void close() { runtime.close(); }
        };
    }

    private static ManagedDependency disabledDependency() {
        return new ManagedDependency() {
            @Override public void start() { }
            @Override public boolean ready() { return true; }
            @Override public void close() { }
        };
    }

    private static BlockingServer blocking(V2GatewayServer server) {
        return new BlockingServer() {
            @Override
            public void start() {
                server.start();
            }

            @Override
            public void awaitClose() {
                server.awaitClose();
            }

            @Override
            public void stopAccepting() {
                server.stopAccepting();
            }

            @Override
            public boolean awaitDrained(Duration timeout) {
                return server.awaitDrained(timeout);
            }

            @Override
            public void close() {
                server.close();
            }
        };
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception exception) {
                // Shutdown continues in reverse ownership order; callers log one safe event.
            }
        }
    }

    private static String renderDistributedMetrics(
            DistributedGatewayRoutingComponents components, Clock clock) {
        try {
            var observedAt = clock.instant();
            return "# TYPE chat_gateway_distributed_metrics_available gauge\n"
                    + "chat_gateway_distributed_metrics_available 1\n"
                    + PrometheusGatewayRoutingMetrics.render(
                            components.runtime().leaseTelemetry(),
                            components.consumerTelemetry().snapshot())
                    + PrometheusConversationEventOutboxMetrics.renderRelay(
                            components.relayTelemetry().snapshot())
                    + PrometheusConversationEventOutboxMetrics.render(
                            components.outboxStatus().readStatus(observedAt), observedAt);
        } catch (RuntimeException exception) {
            return "# TYPE chat_gateway_distributed_metrics_available gauge\n"
                    + "chat_gateway_distributed_metrics_available 0\n";
        }
    }

    interface ManagedServer extends AutoCloseable {
        void start();

        @Override
        void close();
    }

    interface BlockingServer extends ManagedServer {
        void awaitClose();

        void stopAccepting();

        boolean awaitDrained(Duration timeout);
    }

    interface ManagedDependency extends AutoCloseable {
        void start();
        boolean ready();
        @Override void close();
    }
}
