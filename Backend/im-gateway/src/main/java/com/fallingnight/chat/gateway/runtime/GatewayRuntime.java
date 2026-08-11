package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.application.identity.AuthenticationService;
import com.fallingnight.chat.application.identity.SessionResumeService;
import com.fallingnight.chat.gateway.operations.GatewayAdminServer;
import com.fallingnight.chat.gateway.transport.AuthenticationTelemetry;
import com.fallingnight.chat.gateway.transport.AuthenticationWorkerPool;
import com.fallingnight.chat.gateway.transport.InMemoryAuthenticationAdmissionControl;
import com.fallingnight.chat.gateway.transport.MessagingWorkerPool;
import com.fallingnight.chat.identity.crypto.Argon2idCredentialHasher;
import com.fallingnight.chat.identity.crypto.CompatibleCredentialVerifier;
import com.fallingnight.chat.persistence.postgres.PostgresIdentityAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresMessageAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresMigrator;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the validated database, workers, admin endpoint, and product listener lifecycle. */
public final class GatewayRuntime implements AutoCloseable {
    private static final Duration WORKER_CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final AtomicBoolean readiness;
    private final ManagedServer admin;
    private final BlockingServer product;
    private final AutoCloseable authenticationWorkers;
    private final AutoCloseable messagingWorkers;
    private final AutoCloseable dataSource;
    private boolean started;
    private boolean closed;

    private GatewayRuntime(
            AtomicBoolean readiness,
            ManagedServer admin,
            BlockingServer product,
            AutoCloseable authenticationWorkers,
            AutoCloseable messagingWorkers,
            AutoCloseable dataSource) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.product = Objects.requireNonNull(product, "product");
        this.authenticationWorkers = Objects.requireNonNull(
                authenticationWorkers, "authenticationWorkers");
        this.messagingWorkers = Objects.requireNonNull(messagingWorkers, "messagingWorkers");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
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
        try {
            dataSource = GatewayPostgresDataSource.create(config);
            PostgresIdentityAdapter identity = new PostgresIdentityAdapter(dataSource);
            PostgresMessageAdapter messages = new PostgresMessageAdapter(dataSource);
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
            InMemoryAuthenticationAdmissionControl admission =
                    new InMemoryAuthenticationAdmissionControl(config.admissionLimits(), clock);
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
                    workers,
                    messagingWorkers,
                    admission,
                    telemetry);
            AtomicBoolean readiness = new AtomicBoolean();
            adminServer = new GatewayAdminServer(
                    config.adminAddress(),
                    config.adminWorkers(),
                    telemetry,
                    readiness::get);
            return new GatewayRuntime(
                    readiness,
                    managed(adminServer),
                    blocking(productServer),
                    workers,
                    messagingWorkers,
                    dataSource);
        } catch (RuntimeException exception) {
            closeQuietly(adminServer);
            closeQuietly(productServer);
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
                readiness, admin, product, authenticationWorkers, messagingWorkers, dataSource);
    }

    public synchronized void start() {
        if (started || closed) {
            throw new IllegalStateException("gateway runtime cannot be started");
        }
        started = true;
        try {
            admin.start();
            product.start();
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
        return readiness.get();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        readiness.set(false);
        closeQuietly(product);
        closeQuietly(admin);
        closeQuietly(messagingWorkers);
        closeQuietly(authenticationWorkers);
        closeQuietly(dataSource);
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

    interface ManagedServer extends AutoCloseable {
        void start();

        @Override
        void close();
    }

    interface BlockingServer extends ManagedServer {
        void awaitClose();
    }
}
