package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PrometheusEventLoopMetricsTest {
    @Test
    void rendersFixedNameEventLoopGauges() {
        String rendered = PrometheusEventLoopMetrics.render(
                new EventLoopSnapshot(true, 4, 10, 2_000_000, 9_000_000, 3));

        assertTrue(rendered.contains("chat_gateway_event_loop_metrics_available 1"));
        assertTrue(rendered.contains("chat_gateway_event_loop_workers 4"));
        assertTrue(rendered.contains("chat_gateway_event_loop_probe_samples_total 10"));
        assertTrue(rendered.contains(
                "chat_gateway_event_loop_latest_max_lag_seconds 0.002000000"));
        assertTrue(rendered.contains(
                "chat_gateway_event_loop_max_lag_seconds 0.009000000"));
        assertTrue(rendered.contains("chat_gateway_event_loop_pending_tasks 3"));
    }

    @Test
    void rejectsImpossibleSnapshots() {
        assertThrows(IllegalArgumentException.class,
                () -> new EventLoopSnapshot(true, 4, 1, 10, 9, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new EventLoopSnapshot(false, 4, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new EventLoopSnapshot(true, 0, 0, 0, 0, 0));
    }
}
