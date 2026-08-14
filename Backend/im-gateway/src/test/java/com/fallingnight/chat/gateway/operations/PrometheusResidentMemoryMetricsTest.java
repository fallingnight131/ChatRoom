package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PrometheusResidentMemoryMetricsTest {
    @Test
    void rendersFixedNameCachedResidentMemoryMetrics() {
        String rendered = PrometheusResidentMemoryMetrics.render(
                new ResidentMemorySnapshot(true, 123_456, 375, 2));

        assertTrue(rendered.contains(
                "chat_gateway_process_resident_memory_available 1"));
        assertTrue(rendered.contains(
                "chat_gateway_process_resident_memory_bytes 123456"));
        assertTrue(rendered.contains(
                "chat_gateway_process_resident_memory_sample_age_seconds 0.375"));
        assertTrue(rendered.contains(
                "chat_gateway_process_resident_memory_read_failures_total 2"));
    }

    @Test
    void rendersUnsupportedProviderWithoutInventingResidentBytes() {
        String rendered = PrometheusResidentMemoryMetrics.render(
                new ResidentMemorySnapshot(false, 0, 500, 0));

        assertTrue(rendered.contains(
                "chat_gateway_process_resident_memory_available 0"));
        assertTrue(rendered.contains(
                "chat_gateway_process_resident_memory_bytes 0"));
        assertTrue(rendered.contains(
                "chat_gateway_process_resident_memory_sample_age_seconds 0.500"));
    }
}
