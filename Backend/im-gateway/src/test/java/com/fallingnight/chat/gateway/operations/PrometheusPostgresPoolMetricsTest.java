package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PrometheusPostgresPoolMetricsTest {
    @Test
    void rendersFixedNamePoolGauges() {
        String rendered = PrometheusPostgresPoolMetrics.render(
                new PostgresPoolSnapshot(true, 3, 2, 5, 4, 8));

        assertTrue(rendered.contains(
                "chat_gateway_postgres_pool_metrics_available 1"));
        assertTrue(rendered.contains("chat_gateway_postgres_connections_active 3"));
        assertTrue(rendered.contains("chat_gateway_postgres_connections_idle 2"));
        assertTrue(rendered.contains("chat_gateway_postgres_connections_total 5"));
        assertTrue(rendered.contains(
                "chat_gateway_postgres_threads_awaiting_connection 4"));
        assertTrue(rendered.contains("chat_gateway_postgres_connections_maximum 8"));
    }

    @Test
    void rendersUnavailablePoolWithoutInventingActivity() {
        String rendered = PrometheusPostgresPoolMetrics.render(
                PostgresPoolSnapshot.unavailable(8));

        assertTrue(rendered.contains(
                "chat_gateway_postgres_pool_metrics_available 0"));
        assertTrue(rendered.contains("chat_gateway_postgres_connections_total 0"));
        assertTrue(rendered.contains("chat_gateway_postgres_connections_maximum 8"));
    }

    @Test
    void rejectsImpossiblePoolSnapshots() {
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresPoolSnapshot(true, -1, 2, 1, 0, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresPoolSnapshot(true, 5, 4, 9, 0, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresPoolSnapshot(true, 9, 0, 8, 0, 8));
        assertThrows(IllegalArgumentException.class,
                () -> PostgresPoolSnapshot.unavailable(0));
    }
}
