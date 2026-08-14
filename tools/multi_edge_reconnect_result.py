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

WORKLOADS = {
    "step-12": (18, 12, 6, 3, 100),
    "step-24": (30, 24, 6, 6, 100),
    "step-48": (54, 48, 6, 12, 100),
}


def validate(value: Any, expected_revision: str | None = None,
             require_clean: bool = False) -> dict[str, Any]:
    root = object_value(value, "result")
    schema = root.get("schemaVersion")
    if schema not in (1, 2, 3, 4, 5, 6, 7, 8):
        raise EvidenceError("schemaVersion must be between 1 and 8")
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
    bounded_scenario = (connections, affected, surviving, batch, interval)
    if schema < 6:
        if "workloadProfile" in scenario:
            raise EvidenceError("schemaVersion below 6 cannot contain workloadProfile")
        if bounded_scenario != WORKLOADS["step-12"]:
            raise EvidenceError("scenario does not match the bounded baseline")
    else:
        workload = scenario.get("workloadProfile")
        if workload not in WORKLOADS:
            raise EvidenceError("workloadProfile is not a fixed ladder step")
        if bounded_scenario != WORKLOADS[workload]:
            raise EvidenceError("scenario does not match its workloadProfile")

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
    if schema >= 3:
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
    if schema < 4 and "eventLoopSaturation" in results:
        raise EvidenceError("schemaVersion below 4 cannot contain eventLoopSaturation")
    if schema >= 4:
        event_loop = object_value(
            results.get("eventLoopSaturation"), "eventLoopSaturation")
        if set(event_loop) != {
                "sampleIntervalMillis", "samples", "metricsUnavailableSamples",
                "workers", "probeSamplesBefore", "probeSamplesAfter",
                "probeSamplesDelta", "latestMaximumLagMicros",
                "sinceStartMaximumLagMicrosBefore",
                "sinceStartMaximumLagMicrosAfter", "pendingTasksMaximum"}:
            raise EvidenceError("eventLoopSaturation fields are invalid")
        if event_loop.get("sampleIntervalMillis") != 5:
            raise EvidenceError("event-loop saturation interval must be 5 ms")
        event_samples = integer(
            event_loop.get("samples"), "eventLoopSaturation.samples", 2)
        if event_samples != saturation["samples"]:
            raise EvidenceError("event-loop saturation sample count disagrees")
        unavailable = integer(
            event_loop.get("metricsUnavailableSamples"),
            "eventLoopSaturation.metricsUnavailableSamples", 0)
        if unavailable != 0:
            raise EvidenceError("event-loop metrics must be available for every sample")
        if event_loop.get("workers") != 4:
            raise EvidenceError("event-loop worker count does not match the scenario")
        probes_before = integer(
            event_loop.get("probeSamplesBefore"),
            "eventLoopSaturation.probeSamplesBefore", 4)
        probes_after = integer(
            event_loop.get("probeSamplesAfter"),
            "eventLoopSaturation.probeSamplesAfter", probes_before + 1)
        if event_loop.get("probeSamplesDelta") != probes_after - probes_before:
            raise EvidenceError("event-loop probe sample reconciliation is invalid")
        latest_lag = integer(
            event_loop.get("latestMaximumLagMicros"),
            "eventLoopSaturation.latestMaximumLagMicros", 0)
        maximum_before = integer(
            event_loop.get("sinceStartMaximumLagMicrosBefore"),
            "eventLoopSaturation.sinceStartMaximumLagMicrosBefore", 0)
        maximum_after = integer(
            event_loop.get("sinceStartMaximumLagMicrosAfter"),
            "eventLoopSaturation.sinceStartMaximumLagMicrosAfter", maximum_before)
        pending = integer(
            event_loop.get("pendingTasksMaximum"),
            "eventLoopSaturation.pendingTasksMaximum", 0)
        if (latest_lag > maximum_after or maximum_after > 5_000_000
                or pending > 100_000):
            raise EvidenceError("event-loop saturation exceeds bounded scenario")
    if schema < 5 and "processResourceSaturation" in results:
        raise EvidenceError("schemaVersion below 5 cannot contain process resources")
    if schema >= 5:
        process = object_value(
            results.get("processResourceSaturation"), "processResourceSaturation")
        if set(process) != {
                "sampleIntervalMillis", "samples", "cpuTimeUnavailableSamples",
                "cpuTimeMicrosBefore", "cpuTimeMicrosAfter", "cpuTimeMicrosDelta",
                "heapUsedBytesBefore", "heapUsedBytesAfter", "heapUsedBytesMaximum",
                "heapCommittedBytesBefore", "heapCommittedBytesAfter",
                "heapMaximumBytes", "uptimeMillisBefore", "uptimeMillisAfter",
                "uptimeMillisDelta", "availableProcessors"}:
            raise EvidenceError("processResourceSaturation fields are invalid")
        if process.get("sampleIntervalMillis") != 5:
            raise EvidenceError("process resource interval must be 5 ms")
        process_samples = integer(
            process.get("samples"), "processResourceSaturation.samples", 2)
        if process_samples != saturation["samples"]:
            raise EvidenceError("process resource sample count disagrees")
        unavailable = integer(
            process.get("cpuTimeUnavailableSamples"),
            "processResourceSaturation.cpuTimeUnavailableSamples", 0)
        if unavailable != 0:
            raise EvidenceError("process CPU time must be available for every sample")
        cpu_before = integer(
            process.get("cpuTimeMicrosBefore"),
            "processResourceSaturation.cpuTimeMicrosBefore", 0)
        cpu_after = integer(
            process.get("cpuTimeMicrosAfter"),
            "processResourceSaturation.cpuTimeMicrosAfter", cpu_before)
        if process.get("cpuTimeMicrosDelta") != cpu_after - cpu_before:
            raise EvidenceError("process CPU time reconciliation is invalid")
        heap_before = integer(
            process.get("heapUsedBytesBefore"),
            "processResourceSaturation.heapUsedBytesBefore", 0)
        heap_after = integer(
            process.get("heapUsedBytesAfter"),
            "processResourceSaturation.heapUsedBytesAfter", 0)
        heap_peak = integer(
            process.get("heapUsedBytesMaximum"),
            "processResourceSaturation.heapUsedBytesMaximum", 0)
        committed_before = integer(
            process.get("heapCommittedBytesBefore"),
            "processResourceSaturation.heapCommittedBytesBefore", 1)
        committed_after = integer(
            process.get("heapCommittedBytesAfter"),
            "processResourceSaturation.heapCommittedBytesAfter", 1)
        heap_max = integer(
            process.get("heapMaximumBytes"),
            "processResourceSaturation.heapMaximumBytes", 1)
        if (heap_peak < max(heap_before, heap_after)
                or heap_before > committed_before or heap_after > committed_after
                or max(committed_before, committed_after) > heap_max):
            raise EvidenceError("JVM heap resource reconciliation is invalid")
        uptime_before = integer(
            process.get("uptimeMillisBefore"),
            "processResourceSaturation.uptimeMillisBefore", 1)
        uptime_after = integer(
            process.get("uptimeMillisAfter"),
            "processResourceSaturation.uptimeMillisAfter", uptime_before + 1)
        if process.get("uptimeMillisDelta") != uptime_after - uptime_before:
            raise EvidenceError("process uptime reconciliation is invalid")
        processors = integer(
            process.get("availableProcessors"),
            "processResourceSaturation.availableProcessors", 1)
        if processors != environment["availableProcessors"]:
            raise EvidenceError("available processor count disagrees with environment")
    if schema < 7 and "pressureDuration" in results:
        raise EvidenceError("schemaVersion below 7 cannot contain pressureDuration")
    if schema >= 7:
        duration = object_value(results.get("pressureDuration"), "pressureDuration")
        if set(duration) != {
                "sampleIntervalMillis", "samples",
                "authenticationQueuePositiveSamples",
                "authenticationQueueLongestConsecutiveSamples",
                "postgresWaitingPositiveSamples",
                "postgresWaitingLongestConsecutiveSamples",
                "eventLoopPendingPositiveSamples",
                "eventLoopPendingLongestConsecutiveSamples"}:
            raise EvidenceError("pressureDuration fields are invalid")
        if duration.get("sampleIntervalMillis") != 5:
            raise EvidenceError("pressure duration interval must be 5 ms")
        duration_samples = integer(
            duration.get("samples"), "pressureDuration.samples", 2)
        if duration_samples != saturation["samples"]:
            raise EvidenceError("pressure duration sample count disagrees")
        duration_pairs = (
            ("authenticationQueue", queued),
            ("postgresWaiting", awaiting),
            ("eventLoopPending", pending),
        )
        for prefix, peak in duration_pairs:
            positive = integer(
                duration.get(f"{prefix}PositiveSamples"),
                f"pressureDuration.{prefix}PositiveSamples", 0)
            longest = integer(
                duration.get(f"{prefix}LongestConsecutiveSamples"),
                f"pressureDuration.{prefix}LongestConsecutiveSamples", 0)
            if positive > duration_samples or longest > positive:
                raise EvidenceError(f"{prefix} duration exceeds the sample window")
            if (peak == 0) != (positive == 0) or (positive == 0) != (longest == 0):
                raise EvidenceError(f"{prefix} peak and duration do not reconcile")
    if schema < 8 and "gcCollectionActivity" in results:
        raise EvidenceError("schemaVersion below 8 cannot contain GC activity")
    if schema >= 8:
        gc = object_value(results.get("gcCollectionActivity"), "gcCollectionActivity")
        if set(gc) != {
                "sampleIntervalMillis", "samples", "metricsUnavailableSamples",
                "collectionsBefore", "collectionsAfter", "collectionsDelta",
                "collectionTimeMillisBefore", "collectionTimeMillisAfter",
                "collectionTimeMillisDelta"}:
            raise EvidenceError("gcCollectionActivity fields are invalid")
        if gc.get("sampleIntervalMillis") != 5:
            raise EvidenceError("GC collection interval must be 5 ms")
        gc_samples = integer(gc.get("samples"), "gcCollectionActivity.samples", 2)
        if gc_samples != saturation["samples"]:
            raise EvidenceError("GC collection sample count disagrees")
        if integer(gc.get("metricsUnavailableSamples"),
                   "gcCollectionActivity.metricsUnavailableSamples", 0) != 0:
            raise EvidenceError("GC collection metrics must be available for every sample")
        collections_before = integer(
            gc.get("collectionsBefore"), "gcCollectionActivity.collectionsBefore", 0)
        collections_after = integer(
            gc.get("collectionsAfter"), "gcCollectionActivity.collectionsAfter",
            collections_before)
        if gc.get("collectionsDelta") != collections_after - collections_before:
            raise EvidenceError("GC collection count delta does not reconcile")
        time_before = integer(
            gc.get("collectionTimeMillisBefore"),
            "gcCollectionActivity.collectionTimeMillisBefore", 0)
        time_after = integer(
            gc.get("collectionTimeMillisAfter"),
            "gcCollectionActivity.collectionTimeMillisAfter", time_before)
        if gc.get("collectionTimeMillisDelta") != time_after - time_before:
            raise EvidenceError("GC collection time delta does not reconcile")
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
