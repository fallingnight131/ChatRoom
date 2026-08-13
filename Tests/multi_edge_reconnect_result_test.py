#!/usr/bin/env python3

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from multi_edge_reconnect_result import EvidenceError, validate  # noqa: E402


def evidence():
    distribution = {
        "samples": 12, "min": 10, "p50": 20, "p95": 30,
        "p99": 30, "max": 30, "mean": 20.0,
    }
    return {
        "schemaVersion": 1,
        "benchmark": "java-v2-haproxy-multi-edge-reconnect",
        "warning": "local dual-edge recovery evidence; not a production capacity claim",
        "recordedAt": "2026-08-14T00:00:00Z",
        "sourceRevision": "a" * 40,
        "worktreeDirty": False,
        "environment": {
            "javaVersion": "21", "os": "test", "architecture": "arm64",
            "availableProcessors": 10, "maximumHeapBytes": 1024,
        },
        "host": {
            "platform": "test", "pythonVersion": "3.9",
            "haproxyImage": (
                "haproxy:3.2-alpine@sha256:"
                "79799e8b2977e60802774fa53d29e6b54e045402cdd8a8b9fe43923e7095a047"
            ),
        },
        "scenario": {
            "edgeProcesses": 2, "gatewayProcesses": 2,
            "primaryEdgeKilled": True, "connections": 18,
            "failedEdgeConnections": 12, "survivingEdgeConnections": 6,
            "reconnectBatchSize": 3, "reconnectBatchIntervalMillis": 100,
            "reconnectBatches": 4, "scheduledReconnectSpanMillis": 300,
        },
        "results": {
            "reconnectAttempts": 12, "reconnectSuccesses": 12,
            "reconnectErrors": 0,
            "secondaryGatewayAuthenticationBefore": 6,
            "secondaryGatewayAuthenticationAfter": 18,
            "elapsedMillis": 350.0, "reconnectThroughputPerSecond": 34.2,
            "sessionResumeLatencyMicros": copy.deepcopy(distribution),
            "scheduledStartJitterMicros": copy.deepcopy(distribution),
        },
    }


class MultiEdgeReconnectResultTest(unittest.TestCase):
    def test_accepts_reconciled_clean_evidence(self):
        validate(evidence(), "a" * 40, require_clean=True)

    def test_accepts_in_window_saturation_evidence(self):
        value = evidence()
        value["schemaVersion"] = 2
        value["results"]["authenticationSaturation"] = {
            "sampleIntervalMillis": 5,
            "samples": 24,
            "activeWorkersMaximum": 3,
            "queuedWorkMaximum": 0,
        }
        validate(value, "a" * 40, require_clean=True)

    def test_rejects_saturation_extension_without_schema_upgrade(self):
        value = evidence()
        value["results"]["authenticationSaturation"] = {
            "sampleIntervalMillis": 5,
            "samples": 24,
            "activeWorkersMaximum": 3,
            "queuedWorkMaximum": 0,
        }
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)

    def test_rejects_missing_or_invalid_in_window_saturation(self):
        value = evidence()
        value["schemaVersion"] = 2
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)

        for mutate in (
            lambda saturation: saturation.update(sampleIntervalMillis=10),
            lambda saturation: saturation.update(samples=1),
            lambda saturation: saturation.update(activeWorkersMaximum=0),
            lambda saturation: saturation.update(queuedWorkMaximum=13),
            lambda saturation: saturation.update(extraField=1),
        ):
            value = evidence()
            value["schemaVersion"] = 2
            saturation = {
                "sampleIntervalMillis": 5,
                "samples": 24,
                "activeWorkersMaximum": 3,
                "queuedWorkMaximum": 0,
            }
            value["results"]["authenticationSaturation"] = saturation
            mutate(saturation)
            with self.subTest(saturation=saturation), self.assertRaises(EvidenceError):
                validate(value, "a" * 40, require_clean=True)

    def test_rejects_topology_reconciliation_and_distribution_errors(self):
        for mutate in (
            lambda value: value["scenario"].update(edgeProcesses=1),
            lambda value: value["scenario"].update(primaryEdgeKilled=False),
            lambda value: value["scenario"].update(connections=17),
            lambda value: value["scenario"].update(reconnectBatchSize=2),
            lambda value: value["results"].update(reconnectErrors=1),
            lambda value: value["results"].update(
                secondaryGatewayAuthenticationAfter=17),
            lambda value: value["results"]["sessionResumeLatencyMicros"].update(p95=5),
        ):
            value = evidence()
            mutate(value)
            with self.subTest(value=value), self.assertRaises(EvidenceError):
                validate(value, "a" * 40, require_clean=True)


if __name__ == "__main__":
    unittest.main()
