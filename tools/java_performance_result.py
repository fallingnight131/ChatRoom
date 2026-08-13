#!/usr/bin/env python3
"""Validate machine-readable Java V2 PostgreSQL performance evidence."""

from __future__ import annotations

import argparse
import json
import math
import re
from datetime import datetime
from pathlib import Path
from typing import Any


REVISION = re.compile(r"[0-9a-f]{40}")
DISTRIBUTIONS = (
    ("sequentialAppendLatencyMicros", "appendOperations"),
    ("idempotentRetryLatencyMicros", "retryOperations"),
    ("concurrentAppendLatencyMicros", "concurrentOperations"),
    ("historyReadLatencyMicros", "historyReads"),
)


class EvidenceError(ValueError):
    pass


def integer(value: Any, name: str, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise EvidenceError(f"{name} must be an integer >= {minimum}")
    return value


def number(value: Any, name: str, positive: bool = False) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvidenceError(f"{name} must be numeric")
    result = float(value)
    if not math.isfinite(result) or (positive and result <= 0):
        raise EvidenceError(f"{name} must be finite{' and positive' if positive else ''}")
    return result


def object_value(value: Any, name: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise EvidenceError(f"{name} must be an object")
    return value


def timestamp(value: Any, name: str) -> None:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise EvidenceError(f"{name} must be a UTC timestamp")
    try:
        parsed = datetime.fromisoformat(value.removesuffix("Z") + "+00:00")
    except ValueError as error:
        raise EvidenceError(f"{name} is invalid") from error
    if parsed.utcoffset() is None:
        raise EvidenceError(f"{name} must carry UTC offset")


def validate(
    result: Any, expected_revision: str | None = None, require_clean: bool = False
) -> dict[str, Any]:
    root = object_value(result, "result")
    if root.get("schemaVersion") != 1:
        raise EvidenceError("schemaVersion must be 1")
    if root.get("benchmark") != "java-v2-postgres-messaging":
        raise EvidenceError("benchmark identity is invalid")
    if root.get("warning") != "loopback development evidence; not a capacity claim":
        raise EvidenceError("capacity warning is missing")
    revision = root.get("sourceRevision")
    if not isinstance(revision, str) or not REVISION.fullmatch(revision):
        raise EvidenceError("sourceRevision must be a lowercase Git SHA-1")
    if expected_revision is not None and revision != expected_revision:
        raise EvidenceError("sourceRevision does not match the expected revision")
    if not isinstance(root.get("worktreeDirty"), bool):
        raise EvidenceError("worktreeDirty must be boolean")
    if require_clean and root["worktreeDirty"]:
        raise EvidenceError("evidence was generated from a dirty worktree")
    timestamp(root.get("startedAt"), "startedAt")
    timestamp(root.get("recordedAt"), "recordedAt")

    environment = object_value(root.get("environment"), "environment")
    for field in ("javaVersion", "vm", "os", "osVersion", "architecture"):
        if not isinstance(environment.get(field), str) or not environment[field]:
            raise EvidenceError(f"environment.{field} must be non-empty")
    integer(environment.get("availableProcessors"), "availableProcessors", 1)
    integer(environment.get("maximumHeapBytes"), "maximumHeapBytes", 1)
    integer(environment.get("peakObservedHeapBytes"), "peakObservedHeapBytes", 1)
    number(environment.get("processCpuSeconds"), "processCpuSeconds")
    number(environment.get("scenarioWallSeconds"), "scenarioWallSeconds", positive=True)

    host = object_value(root.get("host"), "host")
    for field in ("platform", "pythonVersion"):
        if not isinstance(host.get(field), str) or not host[field]:
            raise EvidenceError(f"host.{field} must be non-empty")
    integer(host.get("javaPeakRssBytes"), "javaPeakRssBytes", 1)
    integer(host.get("postgresPostmasterPeakRssBytes"),
            "postgresPostmasterPeakRssBytes", 1)

    scenario = object_value(root.get("scenario"), "scenario")
    warmup = integer(scenario.get("warmupOperations"), "warmupOperations")
    append = integer(scenario.get("appendOperations"), "appendOperations", 1)
    retry = integer(scenario.get("retryOperations"), "retryOperations", 1)
    concurrent = integer(scenario.get("concurrentOperations"), "concurrentOperations", 1)
    concurrency = integer(scenario.get("concurrency"), "concurrency", 1)
    history = integer(scenario.get("historyReads"), "historyReads", 1)
    integer(scenario.get("payloadBytes"), "payloadBytes", 1)
    if concurrency > concurrent or scenario.get("historyPageSize") != 100:
        raise EvidenceError("scenario concurrency or history page is invalid")
    if scenario.get("durableMessages") != warmup + append + 1 + concurrent:
        raise EvidenceError("durable message reconciliation is invalid")

    results = object_value(root.get("results"), "results")
    expected_samples = {
        "appendOperations": append,
        "retryOperations": retry,
        "concurrentOperations": concurrent,
        "historyReads": history,
    }
    for distribution_name, sample_name in DISTRIBUTIONS:
        distribution = object_value(results.get(distribution_name), distribution_name)
        if distribution.get("samples") != expected_samples[sample_name]:
            raise EvidenceError(f"{distribution_name} sample count is invalid")
        ordered = [
            number(distribution.get(field), f"{distribution_name}.{field}", positive=True)
            for field in ("min", "p50", "p95", "p99", "max")
        ]
        if ordered != sorted(ordered):
            raise EvidenceError(f"{distribution_name} percentiles are not monotonic")
        mean = number(distribution.get("mean"), f"{distribution_name}.mean", positive=True)
        if mean < ordered[0] or mean > ordered[-1]:
            raise EvidenceError(f"{distribution_name} mean is out of range")
    number(results.get("sequentialAppendThroughputPerSecond"),
           "sequentialAppendThroughputPerSecond", positive=True)
    number(results.get("concurrentAppendThroughputPerSecond"),
           "concurrentAppendThroughputPerSecond", positive=True)
    if results.get("concurrentErrors") != 0:
        raise EvidenceError("concurrentErrors must be zero")
    serialized = json.dumps(root, sort_keys=True)
    for forbidden in ("jdbc:postgresql", "password", "token", "sessionId", "accountId"):
        if forbidden.lower() in serialized.lower():
            raise EvidenceError(f"evidence contains forbidden field/content: {forbidden}")
    return root


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result", type=Path)
    parser.add_argument("--source-revision")
    parser.add_argument("--require-clean", action="store_true")
    args = parser.parse_args()
    validate(json.loads(args.result.read_text(encoding="utf-8")),
             args.source_revision, args.require_clean)
    print("Java performance evidence passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (EvidenceError, json.JSONDecodeError, OSError) as error:
        print(f"Java performance evidence failed: {error}")
        raise SystemExit(1)
