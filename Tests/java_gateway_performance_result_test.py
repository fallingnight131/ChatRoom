#!/usr/bin/env python3

import copy
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from java_gateway_performance_result import EvidenceError, validate


REVISION = "a" * 40


def valid_result() -> dict:
    distribution = {
        "samples": 3, "min": 1, "p50": 2, "p95": 3,
        "p99": 4, "max": 5, "mean": 2.5,
    }
    setup = dict(distribution, samples=2)
    return {
        "schemaVersion": 1,
        "benchmark": "java-v2-gateway-messaging",
        "startedAt": "2026-08-14T00:00:00Z",
        "recordedAt": "2026-08-14T00:00:01Z",
        "sourceRevision": REVISION,
        "worktreeDirty": False,
        "warning": "loopback development evidence; not a capacity claim",
        "environment": {
            "javaVersion": "21", "vm": "OpenJDK", "os": "Linux",
            "osVersion": "1", "architecture": "x86_64", "availableProcessors": 4,
            "maximumHeapBytes": 100, "peakObservedHeapBytes": 50,
            "processCpuSeconds": 1.0, "scenarioWallSeconds": 2.0,
        },
        "host": {
            "platform": "Linux", "pythonVersion": "3.12",
            "javaPeakRssBytes": 100, "postgresPostmasterPeakRssBytes": 100,
        },
        "scenario": {
            "connections": 2, "receiversPerMessage": 1,
            "warmupOperations": 1, "messageOperations": 3,
            "payloadBytes": 256, "durableMessages": 4,
        },
        "results": {
            "connectionSetupLatencyMicros": setup,
            "submitToAcceptLatencyMicros": distribution,
            "submitToPeerPublishLatencyMicros": distribution,
            "completedMessageThroughputPerSecond": 10.0,
            "errors": 0,
        },
    }


def valid_group_result() -> dict:
    result = copy.deepcopy(valid_result())
    result["schemaVersion"] = 2
    result["scenario"].update({
        "connections": 5,
        "receiversPerMessage": 4,
        "conversationKind": "GROUP",
    })
    result["results"]["connectionSetupLatencyMicros"]["samples"] = 5
    result["results"]["submitToAllPeersPublishedLatencyMicros"] = (
        result["results"].pop("submitToPeerPublishLatencyMicros")
    )
    result["results"]["peerPublications"] = 12
    return result


class GatewayPerformanceEvidenceTest(unittest.TestCase):
    def test_accepts_valid_clean_evidence(self) -> None:
        self.assertEqual(REVISION, validate(
            valid_result(), REVISION, require_clean=True)["sourceRevision"])
        self.assertEqual(2, validate(
            valid_group_result(), REVISION, require_clean=True)["schemaVersion"])

    def test_rejects_semantic_mismatch_and_secret_content(self) -> None:
        mutations = []
        wrong_messages = copy.deepcopy(valid_result())
        wrong_messages["scenario"]["durableMessages"] = 5
        mutations.append(wrong_messages)
        wrong_samples = copy.deepcopy(valid_result())
        wrong_samples["results"]["submitToPeerPublishLatencyMicros"]["samples"] = 2
        mutations.append(wrong_samples)
        errors = copy.deepcopy(valid_result())
        errors["results"]["errors"] = 1
        mutations.append(errors)
        leak = copy.deepcopy(valid_result())
        leak["host"]["certificate"] = "/tmp/cert.pem"
        mutations.append(leak)
        wrong_group = valid_group_result()
        wrong_group["results"]["peerPublications"] = 11
        mutations.append(wrong_group)
        oversized_group = valid_group_result()
        oversized_group["scenario"]["receiversPerMessage"] = 60
        oversized_group["scenario"]["connections"] = 61
        mutations.append(oversized_group)
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with self.assertRaises(EvidenceError):
                    validate(mutation, REVISION)

    def test_clean_gate_rejects_dirty_development_result(self) -> None:
        dirty = valid_result()
        dirty["worktreeDirty"] = True
        validate(dirty, REVISION)
        with self.assertRaises(EvidenceError):
            validate(dirty, REVISION, require_clean=True)


if __name__ == "__main__":
    unittest.main()
