package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PrometheusProcessResourceMetricsTest {
    @Test
    void rendersFixedNamePortableProcessMetrics() {
        String rendered = PrometheusProcessResourceMetrics.render(
                new ProcessResourceSnapshot(
                        true, 1_250_000_000L, 100, 200, 400, 2_500, 8,
                        true, 12, 345));

        assertTrue(rendered.contains("chat_gateway_process_cpu_time_available 1"));
        assertTrue(rendered.contains("chat_gateway_process_cpu_seconds_total 1.250000000"));
        assertTrue(rendered.contains("chat_gateway_jvm_heap_used_bytes 100"));
        assertTrue(rendered.contains("chat_gateway_jvm_heap_committed_bytes 200"));
        assertTrue(rendered.contains("chat_gateway_jvm_heap_maximum_bytes 400"));
        assertTrue(rendered.contains("chat_gateway_process_uptime_seconds 2.500"));
        assertTrue(rendered.contains("chat_gateway_process_available_processors 8"));
        assertTrue(rendered.contains("chat_gateway_jvm_gc_metrics_available 1"));
        assertTrue(rendered.contains("chat_gateway_jvm_gc_collections_total 12"));
        assertTrue(rendered.contains(
                "chat_gateway_jvm_gc_collection_seconds_total 0.345"));
    }

    @Test
    void rejectsImpossibleResourceSnapshots() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessResourceSnapshot(
                        false, 1, 0, 0, 1, 0, 1, true, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessResourceSnapshot(
                        true, 0, 201, 200, 400, 0, 1, true, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessResourceSnapshot(
                        true, 0, 100, 401, 400, 0, 1, true, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessResourceSnapshot(
                        true, 0, 0, 0, 1, 0, 0, true, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessResourceSnapshot(
                        true, 0, 0, 0, 1, 0, 1, false, 1, 0));
    }
}
