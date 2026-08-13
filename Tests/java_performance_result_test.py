#!/usr/bin/env python3

from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from java_performance_result import EvidenceError, validate  # noqa: E402


def fixture() -> dict[str, object]:
    distribution = {
        "samples": 2, "min": 10, "p50": 10, "p95": 20,
        "p99": 20, "max": 20, "mean": 15.0,
    }
    return {
        "schemaVersion": 1,
        "benchmark": "java-v2-postgres-messaging",
        "startedAt": "2026-08-14T00:00:00Z",
        "recordedAt": "2026-08-14T00:00:01Z",
        "sourceRevision": "a" * 40,
        "worktreeDirty": False,
        "warning": "loopback development evidence; not a capacity claim",
        "environment": {
            "javaVersion": "21", "vm": "test", "os": "test",
            "osVersion": "1", "architecture": "x86_64",
            "availableProcessors": 2, "maximumHeapBytes": 100,
            "peakObservedHeapBytes": 50, "processCpuSeconds": 0.5,
            "scenarioWallSeconds": 1.0,
        },
        "host": {
            "platform": "test", "pythonVersion": "3.12",
            "javaPeakRssBytes": 100, "postgresPostmasterPeakRssBytes": 100,
        },
        "scenario": {
            "warmupOperations": 1, "appendOperations": 2,
            "retryOperations": 2, "concurrentOperations": 2,
            "concurrency": 2, "historyReads": 2, "historyPageSize": 100,
            "payloadBytes": 64, "durableMessages": 6,
        },
        "results": {
            "sequentialAppendLatencyMicros": copy.deepcopy(distribution),
            "idempotentRetryLatencyMicros": copy.deepcopy(distribution),
            "concurrentAppendLatencyMicros": copy.deepcopy(distribution),
            "historyReadLatencyMicros": copy.deepcopy(distribution),
            "sequentialAppendThroughputPerSecond": 2.0,
            "concurrentAppendThroughputPerSecond": 2.0,
            "concurrentErrors": 0,
        },
    }


class JavaPerformanceResultTest(unittest.TestCase):
    def test_accepts_complete_non_capacity_evidence(self) -> None:
        self.assertEqual("a" * 40, validate(fixture(), "a" * 40)["sourceRevision"])

    def test_rejects_incomplete_or_misleading_evidence(self) -> None:
        cases = []
        wrong_revision = fixture(); wrong_revision["sourceRevision"] = "b" * 40
        cases.append(wrong_revision)
        errors = fixture(); errors["results"]["concurrentErrors"] = 1  # type: ignore[index]
        cases.append(errors)
        count = fixture(); count["scenario"]["durableMessages"] = 5  # type: ignore[index]
        cases.append(count)
        percentile = fixture()
        percentile["results"]["historyReadLatencyMicros"]["p99"] = 5  # type: ignore[index]
        cases.append(percentile)
        leaked = fixture(); leaked["host"]["jdbc"] = "jdbc:postgresql://secret"  # type: ignore[index]
        cases.append(leaked)
        for value in cases:
            with self.subTest(value=value), self.assertRaises(EvidenceError):
                validate(value, "a" * 40)

    def test_clean_evidence_gate_rejects_dirty_worktree(self) -> None:
        value = fixture(); value["worktreeDirty"] = True
        self.assertEqual("a" * 40, validate(value)["sourceRevision"])
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)


if __name__ == "__main__":
    unittest.main()
