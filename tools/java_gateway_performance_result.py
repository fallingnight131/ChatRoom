#!/usr/bin/env python3
"""Validate machine-readable Java V2 single-gateway performance evidence."""

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
    schema = root.get("schemaVersion")
    if schema not in (1, 2):
        raise EvidenceError("schemaVersion must be 1 or 2")
    if root.get("benchmark") != "java-v2-gateway-messaging":
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
    receivers = integer(scenario.get("receiversPerMessage"), "receiversPerMessage", 1)
    if receivers > 59:
        raise EvidenceError("receiversPerMessage exceeds the default peer admission window")
    if scenario.get("connections") != receivers + 1:
        raise EvidenceError("gateway connection count must include sender and receivers")
    if schema == 1 and receivers != 1:
        raise EvidenceError("schema 1 requires exactly one receiver")
    if schema == 2 and (receivers < 2 or scenario.get("conversationKind") != "GROUP"):
        raise EvidenceError("schema 2 requires a multi-receiver group")
    warmup = integer(scenario.get("warmupOperations"), "warmupOperations")
    messages = integer(scenario.get("messageOperations"), "messageOperations", 1)
    integer(scenario.get("payloadBytes"), "payloadBytes", 1)
    if scenario.get("durableMessages") != warmup + messages:
        raise EvidenceError("durable message reconciliation is invalid")

    results = object_value(root.get("results"), "results")
    distributions = (
        ("connectionSetupLatencyMicros", receivers + 1),
        ("submitToAcceptLatencyMicros", messages),
        ("submitToPeerPublishLatencyMicros" if schema == 1
         else "submitToAllPeersPublishedLatencyMicros", messages),
    )
    for distribution_name, samples in distributions:
        distribution = object_value(results.get(distribution_name), distribution_name)
        if distribution.get("samples") != samples:
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
    number(results.get("completedMessageThroughputPerSecond"),
           "completedMessageThroughputPerSecond", positive=True)
    if results.get("errors") != 0:
        raise EvidenceError("errors must be zero")
    if schema == 2 and results.get("peerPublications") != messages * receivers:
        raise EvidenceError("group peer publication count is invalid")

    serialized = json.dumps(root, sort_keys=True)
    for forbidden in (
        "jdbc:postgresql", "password", "token", "sessionId", "accountId",
        "certificate", "privateKey",
    ):
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
    print("Java gateway performance evidence passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (EvidenceError, json.JSONDecodeError, OSError) as error:
        print(f"Java gateway performance evidence failed: {error}")
        raise SystemExit(1)
