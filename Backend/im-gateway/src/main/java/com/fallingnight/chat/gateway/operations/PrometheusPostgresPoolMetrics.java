package com.fallingnight.chat.gateway.operations;

import java.util.Objects;

/** Fixed-name Prometheus rendering for the gateway-owned PostgreSQL pool. */
public final class PrometheusPostgresPoolMetrics {
    private PrometheusPostgresPoolMetrics() {}

    public static String render(PostgresPoolSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return "# TYPE chat_gateway_postgres_pool_metrics_available gauge\n"
                + "chat_gateway_postgres_pool_metrics_available "
                + (snapshot.available() ? 1 : 0) + "\n"
                + "# TYPE chat_gateway_postgres_connections_active gauge\n"
                + "chat_gateway_postgres_connections_active "
                + snapshot.activeConnections() + "\n"
                + "# TYPE chat_gateway_postgres_connections_idle gauge\n"
                + "chat_gateway_postgres_connections_idle "
                + snapshot.idleConnections() + "\n"
                + "# TYPE chat_gateway_postgres_connections_total gauge\n"
                + "chat_gateway_postgres_connections_total "
                + snapshot.totalConnections() + "\n"
                + "# TYPE chat_gateway_postgres_threads_awaiting_connection gauge\n"
                + "chat_gateway_postgres_threads_awaiting_connection "
                + snapshot.threadsAwaitingConnection() + "\n"
                + "# TYPE chat_gateway_postgres_connections_maximum gauge\n"
                + "chat_gateway_postgres_connections_maximum "
                + snapshot.maximumConnections() + "\n";
    }
}
