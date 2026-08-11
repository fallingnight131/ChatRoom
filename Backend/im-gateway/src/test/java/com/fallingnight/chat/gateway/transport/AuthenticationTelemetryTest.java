package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthenticationTelemetryTest {
    @Test
    void exportsBoundedMetricsAndPowerOfTwoSafeLogs() {
        List<String> logs = new ArrayList<>();
        AuthenticationTelemetry telemetry = new AuthenticationTelemetry(logs::add);

        telemetry.accepted(true);
        telemetry.rejected();
        telemetry.failed();
        for (int count = 0; count < 4; count++) {
            telemetry.saturated();
        }
        for (int count = 0; count < 3; count++) {
            telemetry.admissionDenied(AuthenticationLimitDimension.ACCOUNT);
        }
        telemetry.completed(AuthenticationOutcome.ACCEPTED, true, 1_000_000);
        telemetry.completed(AuthenticationOutcome.REJECTED, false, 6_000_000);
        telemetry.completed(AuthenticationOutcome.FAILED, false, 6_000_000_000L);

        AuthenticationTelemetrySnapshot snapshot = telemetry.snapshot();
        assertEquals(1, snapshot.accepted());
        assertEquals(1, snapshot.rejected());
        assertEquals(1, snapshot.failed());
        assertEquals(4, snapshot.saturated());
        assertEquals(1, snapshot.credentialUpgradePending());
        assertEquals(3, snapshot.admissionDenials().get(AuthenticationLimitDimension.ACCOUNT));
        assertEquals(1, snapshot.executionDurationBuckets().get("1000000"));
        assertEquals(1, snapshot.executionDurationBuckets().get("10000000"));
        assertEquals(1, snapshot.executionDurationBuckets().get("+Inf"));
        assertEquals(3, snapshot.executionDurationCount());
        assertEquals(6_007_000_000L, snapshot.executionDurationTotalNanos());
        assertEquals(6_000_000_000L, snapshot.executionDurationMaxNanos());

        assertEquals(List.of(
                "event=authentication_saturated count=1",
                "event=authentication_saturated count=2",
                "event=authentication_saturated count=4",
                "event=authentication_admission_denied dimension=account count=1",
                "event=authentication_admission_denied dimension=account count=2"), logs);
        assertFalse(String.join(" ", logs).contains("alice"));
        assertFalse(String.join(" ", logs).contains("192.0.2.1"));
    }
}
