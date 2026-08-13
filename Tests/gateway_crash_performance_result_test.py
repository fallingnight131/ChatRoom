#!/usr/bin/env python3

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from gateway_crash_performance_result import EvidenceError, validate  # noqa: E402


def evidence():
    distribution = {
        "samples": 6, "min": 10, "p50": 20, "p95": 30,
        "p99": 30, "max": 30, "mean": 20.0,
    }
    return {
        "schemaVersion": 1,
        "benchmark": "java-v2-haproxy-crash-reconnect",
        "warning": "local failure-recovery evidence; not a production capacity claim",
        "recordedAt": "2026-08-14T00:00:00Z",
        "sourceRevision": "a" * 40,
        "worktreeDirty": False,
        "environment": {
            "javaVersion": "21", "os": "test", "architecture": "arm64",
            "availableProcessors": 10, "maximumHeapBytes": 1024,
        },
        "host": {
            "platform": "test", "pythonVersion": "3.9",
            "haproxyImage": "haproxy@sha256:test",
        },
        "scenario": {
            "connections": 12, "failedGatewayConnections": 6,
            "survivingGatewayConnections": 6, "reconnectBatchSize": 2,
            "reconnectBatchIntervalMillis": 100, "reconnectBatches": 3,
            "scheduledReconnectSpanMillis": 200,
        },
        "results": {
            "reconnectAttempts": 6, "reconnectSuccesses": 6,
            "reconnectErrors": 0, "elapsedMillis": 250.0,
            "reconnectThroughputPerSecond": 24.0,
            "sessionResumeLatencyMicros": copy.deepcopy(distribution),
            "scheduledStartJitterMicros": copy.deepcopy(distribution),
        },
    }


class GatewayCrashPerformanceResultTest(unittest.TestCase):
    def test_accepts_reconciled_clean_evidence(self):
        validate(evidence(), "a" * 40, require_clean=True)

    def test_rejects_dirty_revision_errors_and_bad_distribution(self):
        for mutate in (
            lambda value: value.update(worktreeDirty=True),
            lambda value: value["results"].update(reconnectErrors=1),
            lambda value: value["scenario"].update(reconnectBatches=2),
            lambda value: value["results"]["sessionResumeLatencyMicros"].update(p95=5),
        ):
            value = evidence()
            mutate(value)
            with self.subTest(value=value), self.assertRaises(EvidenceError):
                validate(value, "a" * 40, require_clean=True)


if __name__ == "__main__":
    unittest.main()
