#!/usr/bin/env python3
"""Validate machine-readable dual-edge reconnect evidence."""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path
from typing import Any

from gateway_crash_performance_result import (
    EvidenceError,
    REVISION,
    distribution,
    integer,
    number,
    object_value,
)

EXPECTED_HAPROXY_IMAGE = (
    "haproxy:3.2-alpine@sha256:"
    "79799e8b2977e60802774fa53d29e6b54e045402cdd8a8b9fe43923e7095a047"
)


def validate(value: Any, expected_revision: str | None = None,
             require_clean: bool = False) -> dict[str, Any]:
    root = object_value(value, "result")
    schema = root.get("schemaVersion")
    if schema not in (1, 2, 3):
        raise EvidenceError("schemaVersion must be 1, 2, or 3")
    if root.get("benchmark") != "java-v2-haproxy-multi-edge-reconnect":
        raise EvidenceError("benchmark identity is invalid")
    if root.get("warning") != (
            "local dual-edge recovery evidence; not a production capacity claim"):
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
    if host["haproxyImage"] != EXPECTED_HAPROXY_IMAGE:
        raise EvidenceError("HAProxy image does not match the benchmark identity")

    scenario = object_value(root.get("scenario"), "scenario")
    if scenario.get("edgeProcesses") != 2 or scenario.get("gatewayProcesses") != 2:
        raise EvidenceError("scenario must contain two edges and two gateways")
    if scenario.get("primaryEdgeKilled") is not True:
        raise EvidenceError("primary edge loss is not recorded")
    connections = integer(scenario.get("connections"), "connections", 3)
    affected = integer(scenario.get("failedEdgeConnections"),
                       "failedEdgeConnections", 2)
    surviving = integer(scenario.get("survivingEdgeConnections"),
                        "survivingEdgeConnections", 1)
    if affected + surviving != connections:
        raise EvidenceError("edge connection reconciliation is invalid")
    batch = integer(scenario.get("reconnectBatchSize"), "reconnectBatchSize", 1)
    if batch >= affected:
        raise EvidenceError("reconnect scenario requires at least two batches")
    interval = integer(scenario.get("reconnectBatchIntervalMillis"),
                       "reconnectBatchIntervalMillis", 1)
    if interval > 5_000:
        raise EvidenceError("reconnect interval exceeds bounded scenario")
    batches = (affected + batch - 1) // batch
    if scenario.get("reconnectBatches") != batches:
        raise EvidenceError("reconnect batch count is invalid")
    if scenario.get("scheduledReconnectSpanMillis") != (batches - 1) * interval:
        raise EvidenceError("scheduled reconnect span is invalid")
    if (connections, affected, surviving, batch, interval) != (18, 12, 6, 3, 100):
        raise EvidenceError("scenario does not match the bounded baseline")

    results = object_value(root.get("results"), "results")
    if results.get("reconnectAttempts") != affected:
        raise EvidenceError("reconnect attempt count is invalid")
    if results.get("reconnectSuccesses") != affected or results.get("reconnectErrors") != 0:
        raise EvidenceError("all reconnects must succeed")
    if results.get("secondaryGatewayAuthenticationBefore") != surviving:
        raise EvidenceError("survivor baseline authentication count is invalid")
    if results.get("secondaryGatewayAuthenticationAfter") != connections:
        raise EvidenceError("survivor final authentication count is invalid")
    number(results.get("elapsedMillis"), "elapsedMillis", True)
    number(results.get("reconnectThroughputPerSecond"),
           "reconnectThroughputPerSecond", True)
    distribution(results.get("sessionResumeLatencyMicros"),
                 "sessionResumeLatencyMicros", affected)
    distribution(results.get("scheduledStartJitterMicros"),
                 "scheduledStartJitterMicros", affected)
    if schema == 1 and "authenticationSaturation" in results:
        raise EvidenceError("schemaVersion 1 cannot contain authenticationSaturation")
    if schema >= 2:
        saturation = object_value(
            results.get("authenticationSaturation"),
            "authenticationSaturation")
        if set(saturation) != {
                "sampleIntervalMillis", "samples", "activeWorkersMaximum",
                "queuedWorkMaximum"}:
            raise EvidenceError("authenticationSaturation fields are invalid")
        if saturation.get("sampleIntervalMillis") != 5:
            raise EvidenceError("authentication saturation interval must be 5 ms")
        integer(saturation.get("samples"), "authenticationSaturation.samples", 2)
        active = integer(
            saturation.get("activeWorkersMaximum"),
            "authenticationSaturation.activeWorkersMaximum", 1)
        queued = integer(
            saturation.get("queuedWorkMaximum"),
            "authenticationSaturation.queuedWorkMaximum", 0)
        if active > affected or queued > affected:
            raise EvidenceError("authentication saturation exceeds bounded workload")
    if schema < 3 and "postgresPoolSaturation" in results:
        raise EvidenceError("schemaVersion below 3 cannot contain postgresPoolSaturation")
    if schema == 3:
        postgres = object_value(
            results.get("postgresPoolSaturation"), "postgresPoolSaturation")
        if set(postgres) != {
                "sampleIntervalMillis", "samples", "metricsUnavailableSamples",
                "activeConnectionsMaximum", "totalConnectionsMaximum",
                "threadsAwaitingConnectionMaximum",
                "configuredMaximumConnections"}:
            raise EvidenceError("postgresPoolSaturation fields are invalid")
        if postgres.get("sampleIntervalMillis") != 5:
            raise EvidenceError("PostgreSQL pool saturation interval must be 5 ms")
        postgres_samples = integer(
            postgres.get("samples"), "postgresPoolSaturation.samples", 2)
        if postgres_samples != saturation["samples"]:
            raise EvidenceError("resource saturation sample counts disagree")
        unavailable = integer(
            postgres.get("metricsUnavailableSamples"),
            "postgresPoolSaturation.metricsUnavailableSamples", 0)
        if unavailable != 0:
            raise EvidenceError("PostgreSQL pool metrics must be available for every sample")
        configured = integer(
            postgres.get("configuredMaximumConnections"),
            "postgresPoolSaturation.configuredMaximumConnections", 1)
        if configured != 4:
            raise EvidenceError("PostgreSQL pool maximum does not match the scenario")
        active_connections = integer(
            postgres.get("activeConnectionsMaximum"),
            "postgresPoolSaturation.activeConnectionsMaximum", 1)
        total_connections = integer(
            postgres.get("totalConnectionsMaximum"),
            "postgresPoolSaturation.totalConnectionsMaximum", 1)
        awaiting = integer(
            postgres.get("threadsAwaitingConnectionMaximum"),
            "postgresPoolSaturation.threadsAwaitingConnectionMaximum", 0)
        if (active_connections > configured or total_connections > configured
                or active_connections > total_connections or awaiting > affected):
            raise EvidenceError("PostgreSQL pool saturation exceeds bounded scenario")
    return root


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    parser.add_argument("--expected-revision")
    parser.add_argument("--require-clean", action="store_true")
    args = parser.parse_args()
    validate(json.loads(args.path.read_text(encoding="utf-8")),
             args.expected_revision, args.require_clean)
    print(f"multi-edge reconnect evidence is valid: {args.path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (EvidenceError, json.JSONDecodeError, OSError, ValueError) as error:
        print(f"multi-edge reconnect evidence is invalid: {error}")
        raise SystemExit(1)
