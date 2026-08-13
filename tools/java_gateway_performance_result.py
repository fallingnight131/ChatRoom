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
    if schema not in (1, 2, 3, 4, 5, 6):
        raise EvidenceError("schemaVersion must be 1, 2, 3, 4, 5, or 6")
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
    saturation_senders = 0
    if schema == 5:
        saturation_senders = integer(
            scenario.get("postgresSaturationSenders"), "postgresSaturationSenders", 2)
        if saturation_senders > 16:
            raise EvidenceError("postgresSaturationSenders exceeds the bounded scenario")
    if scenario.get("connections") != receivers + 1 + saturation_senders:
        raise EvidenceError("gateway connection count must include sender and receivers")
    if schema == 1 and receivers != 1:
        raise EvidenceError("schema 1 requires exactly one receiver")
    if schema == 2 and (receivers < 2 or scenario.get("conversationKind") != "GROUP"):
        raise EvidenceError("schema 2 requires a multi-receiver group")
    if schema == 3 and receivers > 1 and scenario.get("conversationKind") != "GROUP":
        raise EvidenceError("multi-receiver reconnect evidence requires GROUP identity")
    if schema == 4 and (receivers < 2 or scenario.get("conversationKind") != "GROUP"):
        raise EvidenceError("slow-consumer evidence requires a multi-receiver group")
    if schema == 5 and scenario.get("conversationKind") != "GROUP":
        raise EvidenceError("PostgreSQL saturation evidence requires GROUP identity")
    if schema == 6 and receivers > 1 and scenario.get("conversationKind") != "GROUP":
        raise EvidenceError("multi-receiver PostgreSQL outage evidence requires GROUP identity")
    warmup = integer(scenario.get("warmupOperations"), "warmupOperations")
    messages = integer(scenario.get("messageOperations"), "messageOperations", 1)
    payload_bytes = integer(scenario.get("payloadBytes"), "payloadBytes", 1)
    if payload_bytes > 65_536:
        raise EvidenceError("payloadBytes exceeds the UTF-8 text messaging limit")
    slow_messages = 0
    if schema == 4:
        slow_max = integer(
            scenario.get("slowConsumerMaxMessages"), "slowConsumerMaxMessages", 1)
        if slow_max > 100:
            raise EvidenceError("slowConsumerMaxMessages exceeds the bounded scenario")
        slow_messages = integer(
            scenario.get("slowConsumerMessagesBeforeClosure"),
            "slowConsumerMessagesBeforeClosure", 1)
        if slow_messages > slow_max:
            raise EvidenceError("slow consumer closure exceeded the configured message bound")
        if scenario.get("slowConsumerHealthyReceivers") != receivers - 1:
            raise EvidenceError("slow consumer healthy receiver count is invalid")
    expected_durable = (warmup + messages + (slow_messages + 1 if schema == 4 else 0)
                        + saturation_senders + (1 if schema == 6 else 0))
    if scenario.get("durableMessages") != expected_durable:
        raise EvidenceError("durable message reconciliation is invalid")

    results = object_value(root.get("results"), "results")
    distributions = (
        ("connectionSetupLatencyMicros", receivers + 1 + saturation_senders),
        ("submitToAcceptLatencyMicros", messages),
        ("submitToPeerPublishLatencyMicros" if scenario.get("conversationKind") != "GROUP"
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
    if receivers > 1 and results.get("peerPublications") != messages * receivers:
        raise EvidenceError("group peer publication count is invalid")
    if schema == 3:
        rounds = integer(scenario.get("reconnectRounds"), "reconnectRounds", 1)
        if rounds > 20:
            raise EvidenceError("reconnectRounds exceeds the bounded scenario")
        if (receivers + 1) * (rounds + 1) > 60:
            raise EvidenceError("initial authentication plus resumes exceed the default peer window")
        operations = (receivers + 1) * rounds
        if scenario.get("reconnectOperations") != operations:
            raise EvidenceError("reconnect operation count is invalid")
        resume = object_value(results.get("sessionResumeLatencyMicros"),
                              "sessionResumeLatencyMicros")
        if resume.get("samples") != operations:
            raise EvidenceError("session resume sample count is invalid")
        ordered = [
            number(resume.get(field), f"sessionResumeLatencyMicros.{field}", positive=True)
            for field in ("min", "p50", "p95", "p99", "max")
        ]
        if ordered != sorted(ordered):
            raise EvidenceError("session resume percentiles are not monotonic")
        mean = number(resume.get("mean"), "sessionResumeLatencyMicros.mean", positive=True)
        if mean < ordered[0] or mean > ordered[-1]:
            raise EvidenceError("session resume mean is out of range")
        number(results.get("sessionResumeThroughputPerSecond"),
               "sessionResumeThroughputPerSecond", positive=True)
        if results.get("resumeErrors") != 0:
            raise EvidenceError("resumeErrors must be zero")
    if schema == 4:
        slow_distribution = object_value(
            results.get("slowConsumerHealthyPublishLatencyMicros"),
            "slowConsumerHealthyPublishLatencyMicros")
        if slow_distribution.get("samples") != slow_messages:
            raise EvidenceError("slow consumer healthy latency sample count is invalid")
        probe_distribution = object_value(
            results.get("slowConsumerRecoveryProbeLatencyMicros"),
            "slowConsumerRecoveryProbeLatencyMicros")
        for distribution_name, distribution, samples in (
            ("slowConsumerHealthyPublishLatencyMicros", slow_distribution, slow_messages),
            ("slowConsumerRecoveryProbeLatencyMicros", probe_distribution, 1),
        ):
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
        if results.get("slowConsumerHealthyPeerPublications") != slow_messages * (receivers - 1):
            raise EvidenceError("slow consumer healthy publication count is invalid")
        if results.get("slowConsumerRecoveredHistoryMessages") != slow_messages:
            raise EvidenceError("slow consumer recovered history count is invalid")
        if results.get("slowConsumerClosed") != 1:
            raise EvidenceError("slow consumer closure count must be exactly one")
        if results.get("slowConsumerErrors") != 0:
            raise EvidenceError("slowConsumerErrors must be zero")
    if schema == 5:
        if scenario.get("postgresPoolMaximum") != 2:
            raise EvidenceError("PostgreSQL saturation pool maximum must be two")
        if scenario.get("postgresConnectionTimeoutMillis") != 1000:
            raise EvidenceError("PostgreSQL saturation connection timeout is invalid")
        if scenario.get("postgresInjectedDelayMillis") != 2000:
            raise EvidenceError("PostgreSQL saturation delay is invalid")
        saturation = object_value(
            results.get("postgresSaturationAcceptLatencyMicros"),
            "postgresSaturationAcceptLatencyMicros")
        if saturation.get("samples") != saturation_senders:
            raise EvidenceError("PostgreSQL saturation latency sample count is invalid")
        ordered = [
            number(saturation.get(field),
                   f"postgresSaturationAcceptLatencyMicros.{field}", positive=True)
            for field in ("min", "p50", "p95", "p99", "max")
        ]
        if ordered != sorted(ordered):
            raise EvidenceError("PostgreSQL saturation percentiles are not monotonic")
        mean = number(saturation.get("mean"),
                      "postgresSaturationAcceptLatencyMicros.mean", positive=True)
        if mean < ordered[0] or mean > ordered[-1]:
            raise EvidenceError("PostgreSQL saturation mean is out of range")
        if results.get("postgresSaturationPeerPublications") != saturation_senders * receivers:
            raise EvidenceError("PostgreSQL saturation publication count is invalid")
        if results.get("postgresSaturationUnavailableReadinessStatus") != 503:
            raise EvidenceError("PostgreSQL saturation must make readiness unavailable")
        if results.get("postgresSaturationRecoveredReadinessStatus") != 200:
            raise EvidenceError("PostgreSQL readiness did not recover")
        retryable_failures = integer(
            results.get("postgresSaturationRetryableFailures"),
            "postgresSaturationRetryableFailures", 1)
        if retryable_failures >= saturation_senders:
            raise EvidenceError("PostgreSQL saturation must retain at least one initial success")
        if results.get("postgresSaturationConvergedRetries") != retryable_failures:
            raise EvidenceError("PostgreSQL saturation retries did not converge")
        if results.get("postgresSaturationErrors") != 0:
            raise EvidenceError("postgresSaturationErrors must be zero")
    if schema == 6:
        if scenario.get("postgresOutage") is not True:
            raise EvidenceError("PostgreSQL outage scenario identity is missing")
        if scenario.get("postgresOutageRetryOnOriginalConnection") is not True:
            raise EvidenceError("PostgreSQL outage must retry on the original connection")
        if scenario.get("postgresPoolMaximum") != 2:
            raise EvidenceError("PostgreSQL outage pool maximum must be two")
        if scenario.get("postgresConnectionTimeoutMillis") != 1000:
            raise EvidenceError("PostgreSQL outage connection timeout is invalid")
        for distribution_name in (
            "postgresOutageFailureLatencyMicros",
            "postgresOutageRecoveryLatencyMicros",
        ):
            distribution = object_value(results.get(distribution_name), distribution_name)
            if distribution.get("samples") != 1:
                raise EvidenceError(f"{distribution_name} sample count is invalid")
            ordered = [
                number(distribution.get(field), f"{distribution_name}.{field}", positive=True)
                for field in ("min", "p50", "p95", "p99", "max")
            ]
            if ordered != sorted(ordered):
                raise EvidenceError(f"{distribution_name} percentiles are not monotonic")
            mean = number(
                distribution.get("mean"), f"{distribution_name}.mean", positive=True)
            if mean < ordered[0] or mean > ordered[-1]:
                raise EvidenceError(f"{distribution_name} mean is out of range")
        if results.get("postgresOutageUnavailableReadinessStatus") != 503:
            raise EvidenceError("PostgreSQL outage must make readiness unavailable")
        if results.get("postgresOutageAvailableLivenessStatus") != 200:
            raise EvidenceError("PostgreSQL outage must preserve gateway liveness")
        if results.get("postgresOutageRecoveredReadinessStatus") != 200:
            raise EvidenceError("PostgreSQL outage readiness did not recover")
        if results.get("postgresOutagePeerPublications") != receivers:
            raise EvidenceError("PostgreSQL outage publication count is invalid")
        if results.get("postgresOutageRetryableFailures") != 1:
            raise EvidenceError("PostgreSQL outage must return one retryable failure")
        if results.get("postgresOutageConvergedRetries") != 1:
            raise EvidenceError("PostgreSQL outage retry did not converge")
        if results.get("postgresOutageErrors") != 0:
            raise EvidenceError("postgresOutageErrors must be zero")

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
