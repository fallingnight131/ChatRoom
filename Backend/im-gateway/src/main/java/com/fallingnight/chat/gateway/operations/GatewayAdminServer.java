package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.gateway.transport.AuthenticationTelemetry;
import com.fallingnight.chat.gateway.transport.MessagingTelemetry;
import com.fallingnight.chat.gateway.transport.DeviceManagementTelemetry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Loopback-only health and metrics server with bounded worker ownership. */
public final class GatewayAdminServer implements AutoCloseable {
    private static final int BACKLOG = 32;
    private final HttpServer server;
    private final ExecutorService executor;

    public GatewayAdminServer(
            InetSocketAddress address,
            int workers,
            AuthenticationTelemetry telemetry,
            MessagingTelemetry messagingTelemetry,
            DeviceManagementTelemetry deviceManagementTelemetry,
            AttachmentCleanupTelemetry attachmentCleanupTelemetry,
            IntSupplier authenticationActiveWorkers,
            IntSupplier authenticationQueuedWork,
            IntSupplier messagingActiveWorkers,
            IntSupplier messagingQueuedWork,
            Supplier<PostgresPoolSnapshot> postgresPoolSnapshot,
            Supplier<EventLoopSnapshot> eventLoopSnapshot,
            Supplier<ProcessResourceSnapshot> processResourceSnapshot,
            BooleanSupplier readiness) {
        this(address, workers, telemetry, messagingTelemetry, deviceManagementTelemetry,
                attachmentCleanupTelemetry, authenticationActiveWorkers,
                authenticationQueuedWork, messagingActiveWorkers, messagingQueuedWork,
                postgresPoolSnapshot, eventLoopSnapshot, processResourceSnapshot,
                readiness, () -> "",
                GatewayReleaseIdentity.fromEnvironment(Map.of()));
    }

    public GatewayAdminServer(
            InetSocketAddress address, int workers, AuthenticationTelemetry telemetry,
            MessagingTelemetry messagingTelemetry,
            DeviceManagementTelemetry deviceManagementTelemetry,
            AttachmentCleanupTelemetry attachmentCleanupTelemetry,
            IntSupplier authenticationActiveWorkers, IntSupplier authenticationQueuedWork,
            IntSupplier messagingActiveWorkers, IntSupplier messagingQueuedWork,
            Supplier<PostgresPoolSnapshot> postgresPoolSnapshot,
            Supplier<EventLoopSnapshot> eventLoopSnapshot,
            Supplier<ProcessResourceSnapshot> processResourceSnapshot,
            BooleanSupplier readiness, Supplier<String> distributedMetrics) {
        this(address, workers, telemetry, messagingTelemetry, deviceManagementTelemetry,
                attachmentCleanupTelemetry, authenticationActiveWorkers,
                authenticationQueuedWork, messagingActiveWorkers, messagingQueuedWork,
                postgresPoolSnapshot, eventLoopSnapshot, processResourceSnapshot,
                readiness, distributedMetrics,
                GatewayReleaseIdentity.fromEnvironment(Map.of()));
    }

    public GatewayAdminServer(
            InetSocketAddress address, int workers, AuthenticationTelemetry telemetry,
            MessagingTelemetry messagingTelemetry,
            DeviceManagementTelemetry deviceManagementTelemetry,
            AttachmentCleanupTelemetry attachmentCleanupTelemetry,
            IntSupplier authenticationActiveWorkers, IntSupplier authenticationQueuedWork,
            IntSupplier messagingActiveWorkers, IntSupplier messagingQueuedWork,
            Supplier<PostgresPoolSnapshot> postgresPoolSnapshot,
            Supplier<EventLoopSnapshot> eventLoopSnapshot,
            Supplier<ProcessResourceSnapshot> processResourceSnapshot,
            BooleanSupplier readiness, Supplier<String> distributedMetrics,
            GatewayReleaseIdentity releaseIdentity) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(telemetry, "telemetry");
        Objects.requireNonNull(messagingTelemetry, "messagingTelemetry");
        Objects.requireNonNull(deviceManagementTelemetry, "deviceManagementTelemetry");
        Objects.requireNonNull(attachmentCleanupTelemetry, "attachmentCleanupTelemetry");
        Objects.requireNonNull(authenticationActiveWorkers, "authenticationActiveWorkers");
        Objects.requireNonNull(authenticationQueuedWork, "authenticationQueuedWork");
        Objects.requireNonNull(messagingActiveWorkers, "messagingActiveWorkers");
        Objects.requireNonNull(messagingQueuedWork, "messagingQueuedWork");
        Objects.requireNonNull(postgresPoolSnapshot, "postgresPoolSnapshot");
        Objects.requireNonNull(eventLoopSnapshot, "eventLoopSnapshot");
        Objects.requireNonNull(processResourceSnapshot, "processResourceSnapshot");
        Objects.requireNonNull(readiness, "readiness");
        Objects.requireNonNull(distributedMetrics, "distributedMetrics");
        Objects.requireNonNull(releaseIdentity, "releaseIdentity");
        if (address.getAddress() == null || !address.getAddress().isLoopbackAddress()) {
            throw new IllegalArgumentException("admin server must bind a resolved loopback address");
        }
        if (workers < 1 || workers > 4) {
            throw new IllegalArgumentException("admin workers must be between 1 and 4");
        }
        try {
            server = HttpServer.create(address, BACKLOG);
        } catch (IOException exception) {
            throw new IllegalStateException("admin server bind failed", exception);
        }
        AtomicInteger sequence = new AtomicInteger();
        executor = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(
                    runnable, "gateway-admin-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/health/live", exchange ->
                text(exchange, "/health/live", 200, "live\n"));
        server.createContext("/health/ready", exchange -> {
            boolean ready = readiness.getAsBoolean();
            text(exchange, "/health/ready", ready ? 200 : 503,
                    ready ? "ready\n" : "not_ready\n");
        });
        server.createContext("/identity", exchange -> json(
                exchange, "/identity", 200, releaseIdentity.json()));
        server.createContext("/metrics", exchange -> text(
                exchange,
                "/metrics",
                200,
                PrometheusAuthenticationMetrics.render(
                        telemetry.snapshot(),
                        authenticationActiveWorkers.getAsInt(),
                        authenticationQueuedWork.getAsInt())
                        + releaseIdentity.prometheus()
                        + PrometheusMessagingMetrics.render(
                                messagingTelemetry.snapshot(),
                                messagingActiveWorkers.getAsInt(),
                                messagingQueuedWork.getAsInt())
                        + PrometheusDeviceManagementMetrics.render(
                                deviceManagementTelemetry.snapshot())
                        + PrometheusAttachmentCleanupMetrics.render(
                                attachmentCleanupTelemetry.snapshot())
                        + PrometheusPostgresPoolMetrics.render(postgresPoolSnapshot.get())
                        + PrometheusEventLoopMetrics.render(eventLoopSnapshot.get())
                        + PrometheusProcessResourceMetrics.render(
                                processResourceSnapshot.get())
                        + distributedMetrics.get()));
    }

    public void start() {
        server.start();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void text(
            HttpExchange exchange,
            String expectedPath,
            int status,
            String body) throws IOException {
        response(exchange, expectedPath, status, body, "text/plain; charset=utf-8");
    }

    private static void json(
            HttpExchange exchange, String expectedPath, int status, String body) throws IOException {
        response(exchange, expectedPath, status, body, "application/json; charset=utf-8");
    }

    private static void response(
            HttpExchange exchange, String expectedPath, int status, String body,
            String contentType) throws IOException {
        try (exchange) {
            if (!expectedPath.equals(exchange.getRequestURI().getPath())
                    || exchange.getRequestURI().getRawQuery() != null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(status, encoded.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(encoded);
            }
        }
    }
}
