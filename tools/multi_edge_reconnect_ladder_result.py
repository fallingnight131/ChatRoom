#!/usr/bin/env python3
"""Build and validate repeated dual-edge reconnect workload-ladder evidence."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from statistics import median
from typing import Any

from gateway_crash_performance_result import EvidenceError, REVISION, object_value
from multi_edge_reconnect_result import WORKLOADS, validate as validate_run

PROFILES = tuple(WORKLOADS)
REPETITIONS = 3
WARNING = "local repeated reconnect ladder; not a production capacity claim"
RULES_V1 = {
    "majorityRuns": 2,
    "eventLoopProbeDelayMicros": 50_000,
    "normalizedCpuBusyRatio": 0.8,
    "latencyP95GrowthRatio": 2.0,
    "latencyP95GrowthMinimumMicros": 10_000,
}
RULES_V2 = {
    **RULES_V1,
    "minimumConsecutivePositiveSamples": 2,
}


def resource_values(run: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any], float]:
    results = run["results"]
    event_loop = results["eventLoopSaturation"]
    process = results["processResourceSaturation"]
    uptime_micros = process["uptimeMillisDelta"] * 1_000
    cpu_ratio = process["cpuTimeMicrosDelta"] / (
        uptime_micros * process["availableProcessors"])
    return results, event_loop, cpu_ratio


def run_summary_v1(run: dict[str, Any]) -> dict[str, Any]:
    results, event_loop, cpu_ratio = resource_values(run)
    signals = {
        "authenticationQueueObserved": (
            results["authenticationSaturation"]["queuedWorkMaximum"] > 0),
        "postgresWaitObserved": (
            results["postgresPoolSaturation"]
            ["threadsAwaitingConnectionMaximum"] > 0),
        "eventLoopBacklogObserved": event_loop["pendingTasksMaximum"] > 0,
        "eventLoopProbeDelayObserved": (
            event_loop["latestMaximumLagMicros"]
            >= RULES_V1["eventLoopProbeDelayMicros"]),
        "cpuBusyObserved": cpu_ratio >= RULES_V1["normalizedCpuBusyRatio"],
    }
    return {
        "latencyP50Micros": results["sessionResumeLatencyMicros"]["p50"],
        "latencyP95Micros": results["sessionResumeLatencyMicros"]["p95"],
        "authenticationActiveMaximum": (
            results["authenticationSaturation"]["activeWorkersMaximum"]),
        "authenticationQueueMaximum": (
            results["authenticationSaturation"]["queuedWorkMaximum"]),
        "postgresWaitingMaximum": (
            results["postgresPoolSaturation"]
            ["threadsAwaitingConnectionMaximum"]),
        "eventLoopLatestLagMaximumMicros": (
            event_loop["latestMaximumLagMicros"]),
        "eventLoopPendingTasksMaximum": event_loop["pendingTasksMaximum"],
        "normalizedCpuRatio": round(cpu_ratio, 6),
        "heapUsedMaximumBytes": results["processResourceSaturation"]
        ["heapUsedBytesMaximum"],
        "pressureSignals": signals,
        "anyPressureSignal": any(signals.values()),
    }


def profile_summary_v1(profile: str, runs: list[dict[str, Any]],
                       baseline_p95: int | None) -> dict[str, Any]:
    summaries = [run_summary_v1(run) for run in runs]
    median_p50 = int(median(item["latencyP50Micros"] for item in summaries))
    median_p95 = int(median(item["latencyP95Micros"] for item in summaries))
    signal_runs = sum(item["anyPressureSignal"] for item in summaries)
    latency_candidate = False
    if baseline_p95 is not None:
        latency_candidate = (
            median_p95 >= baseline_p95 * RULES_V1["latencyP95GrowthRatio"]
            and median_p95 - baseline_p95
            >= RULES_V1["latencyP95GrowthMinimumMicros"])
    return {
        "workloadProfile": profile,
        "affectedConnections": WORKLOADS[profile][1],
        "runs": summaries,
        "medianLatencyP50Micros": median_p50,
        "medianLatencyP95Micros": median_p95,
        "pressureSignalRuns": signal_runs,
        "repeatedPressureObserved": signal_runs >= RULES_V1["majorityRuns"],
        "latencyKneeCandidate": latency_candidate,
    }


def analysis_for_v1(run_evidence: dict[str, list[dict[str, Any]]]) -> dict[str, Any]:
    baseline = profile_summary_v1("step-12", run_evidence["step-12"], None)
    summaries = [baseline]
    for profile in PROFILES[1:]:
        summaries.append(profile_summary_v1(
            profile, run_evidence[profile], baseline["medianLatencyP95Micros"]))
    repeated = next((item["workloadProfile"] for item in summaries
                     if item["repeatedPressureObserved"]), None)
    latency = next((item["workloadProfile"] for item in summaries
                    if item["latencyKneeCandidate"]), None)
    if repeated == "step-12":
        conclusion = "pressure-observed-at-or-below-step-12"
    elif repeated is not None:
        conclusion = f"repeated-pressure-first-observed-at-{repeated}"
    elif latency is not None:
        conclusion = f"latency-knee-candidate-at-{latency}"
    else:
        conclusion = "no-pressure-knee-observed-within-ladder"
    return {
        "rules": RULES_V1,
        "profiles": summaries,
        "firstRepeatedPressureProfile": repeated,
        "firstLatencyKneeCandidateProfile": latency,
        "conclusion": conclusion,
    }


def run_summary_v2(run: dict[str, Any]) -> dict[str, Any]:
    results, event_loop, cpu_ratio = resource_values(run)
    duration = results["pressureDuration"]
    minimum = RULES_V2["minimumConsecutivePositiveSamples"]
    peak_signals = {
        "authenticationQueueObserved": (
            duration["authenticationQueuePositiveSamples"] > 0),
        "postgresWaitObserved": duration["postgresWaitingPositiveSamples"] > 0,
        "eventLoopBacklogObserved": (
            duration["eventLoopPendingPositiveSamples"] > 0),
        "eventLoopProbeDelayObserved": (
            event_loop["latestMaximumLagMicros"]
            >= RULES_V2["eventLoopProbeDelayMicros"]),
        "cpuBusyObserved": cpu_ratio >= RULES_V2["normalizedCpuBusyRatio"],
    }
    sustained_signals = {
        "authenticationQueueSustained": (
            duration["authenticationQueueLongestConsecutiveSamples"] >= minimum),
        "postgresWaitSustained": (
            duration["postgresWaitingLongestConsecutiveSamples"] >= minimum),
        "eventLoopBacklogSustained": (
            duration["eventLoopPendingLongestConsecutiveSamples"] >= minimum),
        "eventLoopProbeDelayObserved": peak_signals["eventLoopProbeDelayObserved"],
        "cpuBusyObserved": peak_signals["cpuBusyObserved"],
    }
    return {
        "latencyP50Micros": results["sessionResumeLatencyMicros"]["p50"],
        "latencyP95Micros": results["sessionResumeLatencyMicros"]["p95"],
        "authenticationActiveMaximum": (
            results["authenticationSaturation"]["activeWorkersMaximum"]),
        "authenticationQueueMaximum": (
            results["authenticationSaturation"]["queuedWorkMaximum"]),
        "postgresWaitingMaximum": (
            results["postgresPoolSaturation"]
            ["threadsAwaitingConnectionMaximum"]),
        "eventLoopLatestLagMaximumMicros": event_loop["latestMaximumLagMicros"],
        "eventLoopPendingTasksMaximum": event_loop["pendingTasksMaximum"],
        "normalizedCpuRatio": round(cpu_ratio, 6),
        "heapUsedMaximumBytes": results["processResourceSaturation"]
        ["heapUsedBytesMaximum"],
        "pressureDuration": {
            "authenticationQueuePositiveSamples": (
                duration["authenticationQueuePositiveSamples"]),
            "authenticationQueueLongestConsecutiveSamples": (
                duration["authenticationQueueLongestConsecutiveSamples"]),
            "postgresWaitingPositiveSamples": (
                duration["postgresWaitingPositiveSamples"]),
            "postgresWaitingLongestConsecutiveSamples": (
                duration["postgresWaitingLongestConsecutiveSamples"]),
            "eventLoopPendingPositiveSamples": (
                duration["eventLoopPendingPositiveSamples"]),
            "eventLoopPendingLongestConsecutiveSamples": (
                duration["eventLoopPendingLongestConsecutiveSamples"]),
        },
        "peakPressureSignals": peak_signals,
        "sustainedPressureSignals": sustained_signals,
        "anyPeakPressureSignal": any(peak_signals.values()),
        "anySustainedPressureSignal": any(sustained_signals.values()),
    }


def profile_summary_v2(profile: str, runs: list[dict[str, Any]],
                       baseline_p95: int | None) -> dict[str, Any]:
    summaries = [run_summary_v2(run) for run in runs]
    median_p50 = int(median(item["latencyP50Micros"] for item in summaries))
    median_p95 = int(median(item["latencyP95Micros"] for item in summaries))
    peak_runs = sum(item["anyPeakPressureSignal"] for item in summaries)
    sustained_runs = sum(item["anySustainedPressureSignal"] for item in summaries)
    latency_candidate = False
    if baseline_p95 is not None:
        latency_candidate = (
            median_p95 >= baseline_p95 * RULES_V2["latencyP95GrowthRatio"]
            and median_p95 - baseline_p95
            >= RULES_V2["latencyP95GrowthMinimumMicros"])
    return {
        "workloadProfile": profile,
        "affectedConnections": WORKLOADS[profile][1],
        "runs": summaries,
        "medianLatencyP50Micros": median_p50,
        "medianLatencyP95Micros": median_p95,
        "peakPressureSignalRuns": peak_runs,
        "sustainedPressureSignalRuns": sustained_runs,
        "repeatedSustainedPressureObserved": (
            sustained_runs >= RULES_V2["majorityRuns"]),
        "latencyKneeCandidate": latency_candidate,
    }


def analysis_for_v2(run_evidence: dict[str, list[dict[str, Any]]]) -> dict[str, Any]:
    baseline = profile_summary_v2("step-12", run_evidence["step-12"], None)
    summaries = [baseline]
    for profile in PROFILES[1:]:
        summaries.append(profile_summary_v2(
            profile, run_evidence[profile], baseline["medianLatencyP95Micros"]))
    repeated = next((item["workloadProfile"] for item in summaries
                     if item["repeatedSustainedPressureObserved"]), None)
    latency = next((item["workloadProfile"] for item in summaries
                    if item["latencyKneeCandidate"]), None)
    if repeated == "step-12":
        conclusion = "sustained-pressure-observed-at-or-below-step-12"
    elif repeated is not None:
        conclusion = f"repeated-sustained-pressure-first-observed-at-{repeated}"
    elif latency is not None:
        conclusion = f"latency-knee-candidate-at-{latency}"
    else:
        conclusion = "no-sustained-pressure-knee-observed-within-ladder"
    return {
        "rules": RULES_V2,
        "profiles": summaries,
        "firstRepeatedSustainedPressureProfile": repeated,
        "firstLatencyKneeCandidateProfile": latency,
        "conclusion": conclusion,
    }


def build(run_evidence: dict[str, list[dict[str, Any]]]) -> dict[str, Any]:
    first = run_evidence["step-12"][0]
    schema = 2 if first["schemaVersion"] == 7 else 1
    all_runs = [run for profile in PROFILES for run in run_evidence[profile]]
    return {
        "schemaVersion": schema,
        "benchmark": "java-v2-haproxy-multi-edge-reconnect-ladder",
        "warning": WARNING,
        "recordedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "sourceRevision": first["sourceRevision"],
        "worktreeDirty": any(run["worktreeDirty"] for run in all_runs),
        "repetitionsPerProfile": REPETITIONS,
        "profileOrder": list(PROFILES),
        "environment": first["environment"],
        "host": first["host"],
        "runEvidence": run_evidence,
        "analysis": (analysis_for_v2(run_evidence) if schema == 2
                     else analysis_for_v1(run_evidence)),
    }


def validate(value: Any, expected_revision: str | None = None,
             require_clean: bool = False) -> dict[str, Any]:
    root = object_value(value, "result")
    schema = root.get("schemaVersion")
    if schema not in (1, 2):
        raise EvidenceError("ladder schemaVersion must be 1 or 2")
    if root.get("benchmark") != "java-v2-haproxy-multi-edge-reconnect-ladder":
        raise EvidenceError("ladder benchmark identity is invalid")
    if root.get("warning") != WARNING:
        raise EvidenceError("ladder capacity warning is missing")
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
        raise EvidenceError("ladder evidence was generated from a dirty worktree")
    if root.get("repetitionsPerProfile") != REPETITIONS:
        raise EvidenceError("ladder requires exactly three runs per profile")
    if root.get("profileOrder") != list(PROFILES):
        raise EvidenceError("ladder profile order is invalid")
    environment = object_value(root.get("environment"), "environment")
    host = object_value(root.get("host"), "host")
    evidence = object_value(root.get("runEvidence"), "runEvidence")
    if tuple(evidence) != PROFILES:
        raise EvidenceError("ladder runEvidence profiles are invalid")
    dirty = False
    child_schemas = set()
    for profile in PROFILES:
        runs = evidence.get(profile)
        if not isinstance(runs, list) or len(runs) != REPETITIONS:
            raise EvidenceError(f"{profile} must contain exactly three runs")
        for run in runs:
            validated = validate_run(run, revision, require_clean)
            expected_child_schema = 5 + schema
            if validated["schemaVersion"] != expected_child_schema:
                raise EvidenceError(
                    "ladder child schema does not match aggregate schema")
            child_schemas.add(validated["schemaVersion"])
            if validated["scenario"]["workloadProfile"] != profile:
                raise EvidenceError("ladder child profile placement is invalid")
            if validated["environment"] != environment or validated["host"] != host:
                raise EvidenceError("ladder runs must share one environment identity")
            dirty = dirty or validated["worktreeDirty"]
    if len(child_schemas) != 1:
        raise EvidenceError("ladder child schema versions must not be mixed")
    if root["worktreeDirty"] != dirty:
        raise EvidenceError("ladder dirty state does not reconcile child runs")
    expected_analysis = (analysis_for_v2(evidence) if schema == 2
                         else analysis_for_v1(evidence))
    if root.get("analysis") != expected_analysis:
        raise EvidenceError("ladder analysis does not reconcile child runs")
    return root


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    parser.add_argument("--expected-revision")
    parser.add_argument("--require-clean", action="store_true")
    args = parser.parse_args()
    validate(json.loads(args.path.read_text(encoding="utf-8")),
             args.expected_revision, args.require_clean)
    print(f"multi-edge reconnect ladder evidence is valid: {args.path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (EvidenceError, json.JSONDecodeError, OSError, ValueError) as error:
        print(f"multi-edge reconnect ladder evidence is invalid: {error}")
        raise SystemExit(1)
