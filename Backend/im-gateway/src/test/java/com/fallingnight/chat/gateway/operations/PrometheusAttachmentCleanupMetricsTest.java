package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.attachment.AttachmentCleanupReport;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PrometheusAttachmentCleanupMetricsTest {
    @Test
    void rendersOnlyFixedOutcomeAndSchedulerLabels() {
        AttachmentCleanupTelemetry telemetry = new AttachmentCleanupTelemetry();
        telemetry.completed(
                new AttachmentCleanupReport(2, 3, 1, 1, 1),
                2,
                Duration.ofSeconds(4));
        telemetry.failed(3, Duration.ofSeconds(8));

        String rendered = PrometheusAttachmentCleanupMetrics.render(telemetry.snapshot());

        assertTrue(rendered.contains("{outcome=\"run\"} 2"));
        assertTrue(rendered.contains("{outcome=\"run_failure\"} 1"));
        assertTrue(rendered.contains("{outcome=\"revoked\"} 2"));
        assertTrue(rendered.contains("{outcome=\"attempted\"} 3"));
        assertTrue(rendered.contains("{outcome=\"deleted\"} 1"));
        assertTrue(rendered.contains("{outcome=\"provider_failure\"} 1"));
        assertTrue(rendered.contains("{outcome=\"confirmation_failure\"} 1"));
        assertTrue(rendered.contains("consecutive_failures 3"));
        assertTrue(rendered.contains("next_delay_seconds 8"));
        assertFalse(rendered.contains("attachmentId"));
        assertFalse(rendered.contains("objectKey"));
    }
}
