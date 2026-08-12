package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AttachmentTelemetryTest {
    @Test
    void countsOnlyFixedOutcomes() {
        AttachmentTelemetry telemetry = new AttachmentTelemetry();
        telemetry.registered(false);
        telemetry.registered(true);
        telemetry.uploadAuthorized();
        telemetry.ready(false);
        telemetry.ready(true);
        telemetry.denied();
        telemetry.conflict();
        telemetry.invalid();
        telemetry.saturated();
        telemetry.failed();

        assertEquals(new AttachmentTelemetrySnapshot(1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
                telemetry.snapshot());
        assertEquals(10, AttachmentTelemetrySnapshot.class.getRecordComponents().length);
    }
}
