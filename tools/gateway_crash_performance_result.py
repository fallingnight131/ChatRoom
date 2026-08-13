#!/usr/bin/env python3
"""Validate machine-readable HAProxy gateway crash/reconnect evidence."""

from __future__ import annotations

import argparse
import json
import math
import re
from datetime import datetime
from pathlib import Path
from typing import Any


REVISION = re.compile(r"[0-9a-f]{40}")


class EvidenceError(ValueError):
    pass


def object_value(value: Any, name: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise EvidenceError(f"{name} must be an object")
    return value


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


def distribution(value: Any, name: str, samples: int) -> None:
    result = object_value(value, name)
    if integer(result.get("samples"), f"{name}.samples", 1) != samples:
        raise EvidenceError(f"{name} sample count is invalid")
    ordered = [number(result.get(field), f"{name}.{field}", True)
               for field in ("min", "p50", "p95", "p99", "max")]
    if ordered != sorted(ordered):
        raise EvidenceError(f"{name} percentiles are not monotonic")
    mean = number(result.get("mean"), f"{name}.mean", True)
    if mean < ordered[0] or mean > ordered[-1]:
        raise EvidenceError(f"{name} mean is out of range")


def validate(value: Any, expected_revision: str | None = None,
             require_clean: bool = False) -> dict[str, Any]:
    root = object_value(value, "result")
    if root.get("schemaVersion") != 1:
        raise EvidenceError("schemaVersion must be 1")
    if root.get("benchmark") != "java-v2-haproxy-crash-reconnect":
        raise EvidenceError("benchmark identity is invalid")
    if root.get("warning") != (
            "local failure-recovery evidence; not a production capacity claim"):
        raise EvidenceError("capacity warning is missing")
    recorded = root.get("recordedAt")
    if not isinstance(recorded, str) or not recorded.endswith("Z"):
        raise EvidenceError("recordedAt must be UTC")
    datetime.fromisoformat(recorded.removesuffix("Z") + "+00:00")
    revision = root.get("sourceRevision")
    if not isinstance(revision, str) or not REVISION.fullmatch(revision):
        raise EvidenceError("sourceRevision must be a Git SHA-1")
    if expected_revision is not None and revision != expected_revision:
        raise EvidenceError("sourceRevision does not match expected revision")
    if not isinstance(root.get("worktreeDirty"), bool):
        raise EvidenceError("worktreeDirty must be boolean")
    if require_clean and root["worktreeDirty"]:
        raise EvidenceError("evidence was generated from a dirty worktree")

    environment = object_value(root.get("environment"), "environment")
    for field in ("javaVersion", "os", "architecture"):
        if not isinstance(environment.get(field), str) or not environment[field]:
            raise EvidenceError(f"environment.{field} must be non-empty")
    integer(environment.get("availableProcessors"), "availableProcessors", 1)
    integer(environment.get("maximumHeapBytes"), "maximumHeapBytes", 1)
    host = object_value(root.get("host"), "host")
    for field in ("platform", "pythonVersion", "haproxyImage"):
        if not isinstance(host.get(field), str) or not host[field]:
            raise EvidenceError(f"host.{field} must be non-empty")

    scenario = object_value(root.get("scenario"), "scenario")
    connections = integer(scenario.get("connections"), "connections", 2)
    failed = integer(scenario.get("failedGatewayConnections"),
                     "failedGatewayConnections", 1)
    surviving = integer(scenario.get("survivingGatewayConnections"),
                        "survivingGatewayConnections", 1)
    if failed + surviving != connections:
        raise EvidenceError("gateway connection reconciliation is invalid")
    batch = integer(scenario.get("reconnectBatchSize"), "reconnectBatchSize", 1)
    if batch >= failed:
        raise EvidenceError("reconnect scenario requires at least two batches")
    interval = integer(scenario.get("reconnectBatchIntervalMillis"),
                       "reconnectBatchIntervalMillis", 1)
    if interval > 5_000:
        raise EvidenceError("reconnect interval exceeds bounded scenario")
    batches = (failed + batch - 1) // batch
    if scenario.get("reconnectBatches") != batches:
        raise EvidenceError("reconnect batch count is invalid")
    if scenario.get("scheduledReconnectSpanMillis") != (batches - 1) * interval:
        raise EvidenceError("scheduled reconnect span is invalid")

    results = object_value(root.get("results"), "results")
    if results.get("reconnectAttempts") != failed:
        raise EvidenceError("reconnect attempt count is invalid")
    if results.get("reconnectSuccesses") != failed or results.get("reconnectErrors") != 0:
        raise EvidenceError("all reconnects must succeed")
    number(results.get("elapsedMillis"), "elapsedMillis", True)
    number(results.get("reconnectThroughputPerSecond"),
           "reconnectThroughputPerSecond", True)
    distribution(results.get("sessionResumeLatencyMicros"),
                 "sessionResumeLatencyMicros", failed)
    distribution(results.get("scheduledStartJitterMicros"),
                 "scheduledStartJitterMicros", failed)
    return root


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    parser.add_argument("--expected-revision")
    parser.add_argument("--require-clean", action="store_true")
    args = parser.parse_args()
    validate(json.loads(args.path.read_text(encoding="utf-8")),
             args.expected_revision, args.require_clean)
    print(f"gateway crash performance evidence is valid: {args.path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (EvidenceError, json.JSONDecodeError, OSError, ValueError) as error:
        print(f"gateway crash performance evidence is invalid: {error}")
        raise SystemExit(1)
