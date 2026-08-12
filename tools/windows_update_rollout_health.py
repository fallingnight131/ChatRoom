#!/usr/bin/env python3
"""Evaluate aggregate Windows update rollout health without mutating a channel."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError
from windows_update_manifest import read_canonical_manifest
from windows_update_release_completion import KEYS as COMPLETION_KEYS


POLICY_KEYS = {"schemaVersion", "channels"}
CHANNEL_POLICY_KEYS = {
    "rolloutSteps", "minObservationSeconds", "minInstallOutcomes",
    "maxInstallFailureBasisPoints", "maxCrashBasisPoints",
    "emergencyInstallFailureCount", "emergencyCrashCount",
}
METRICS_KEYS = {
    "schemaVersion", "evidenceType", "channel", "releaseId", "version",
    "sourceRevision", "manifestSequence", "rolloutPercentage",
    "windowStartedAt", "windowEndedAt", "updateChecks", "eligibleDevices",
    "downloadStarted", "installSucceeded", "installFailed",
    "crashAffectedDevices",
}
RESULT_KEYS = {
    "schemaVersion", "evidenceType", "status", "decision", "reason",
    "channel", "releaseId", "version", "sourceRevision", "manifestSequence",
    "currentRolloutPercentage", "nextRolloutPercentage",
    "installFailureBasisPoints", "crashBasisPoints", "installOutcomes",
    "windowStartedAt", "windowEndedAt", "recordedAt", "completionSha256",
    "metricsSha256", "policySha256",
}


def _read(path: Path, label: str) -> dict[str, object]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ManifestError(f"Windows rollout health {label} is unsafe")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError(f"Windows rollout health {label} has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError(f"Windows rollout health {label} is unreadable") from error
    if not isinstance(value, dict):
        raise ManifestError(f"Windows rollout health {label} must be an object")
    return value


def _time(value: object, label: str) -> datetime:
    try:
        return datetime.strptime(str(value), "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError(f"Windows rollout health {label} is invalid") from error


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _policy(path: Path, channel: str) -> dict[str, object]:
    root = _read(path, "policy")
    if set(root) != POLICY_KEYS or root.get("schemaVersion") != 1:
        raise ManifestError("Windows rollout health policy shape is invalid")
    channels = root.get("channels")
    if not isinstance(channels, dict) or set(channels) != {"stable", "beta"}:
        raise ManifestError("Windows rollout health channels are invalid")
    for name, value in channels.items():
        if not isinstance(value, dict) or set(value) != CHANNEL_POLICY_KEYS:
            raise ManifestError("Windows rollout health channel policy is malformed")
        steps = value["rolloutSteps"]
        integers = [value[key] for key in CHANNEL_POLICY_KEYS - {"rolloutSteps"}]
        if (not isinstance(steps, list) or not steps
                or any(type(item) is not int for item in steps)
                or steps != sorted(set(steps)) or steps[-1] != 100
                or any(item < 1 or item > 100 for item in steps)
                or any(type(item) is not int or item <= 0 for item in integers)
                or value["maxInstallFailureBasisPoints"] > 10000
                or value["maxCrashBasisPoints"] > 10000):
            raise ManifestError("Windows rollout health policy value is invalid")
    return channels[channel]


def _basis_points(numerator: int, denominator: int) -> int:
    return 0 if numerator == 0 else (numerator * 10000 + denominator - 1) // denominator


def evaluate(
    completion_path: Path,
    candidate_root: Path,
    metrics_path: Path,
    policy_path: Path,
    now_utc: datetime,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows rollout health clock must be an exact UTC second")
    completion = _read(completion_path, "promotion completion")
    metrics = _read(metrics_path, "aggregate metrics")
    if (set(completion) != COMPLETION_KEYS
            or completion.get("status") != "production-update-promotion-observed"):
        raise ManifestError("Windows rollout health completion shape is invalid")
    if (set(metrics) != METRICS_KEYS or metrics.get("schemaVersion") != 1
            or metrics.get("evidenceType") != "windows-update-aggregate-health"):
        raise ManifestError("Windows rollout health metrics shape is invalid")
    channel = completion.get("channel")
    if channel not in {"stable", "beta"}:
        raise ManifestError("Windows rollout health channel is invalid")
    policy = _policy(policy_path, channel)
    started = _time(metrics.get("windowStartedAt"), "window start")
    ended = _time(metrics.get("windowEndedAt"), "window end")
    completed = _time(completion.get("completedAt"), "completion time")
    if (started < completed or ended <= started or ended > now_utc
            or now_utc - ended > timedelta(minutes=5)):
        raise ManifestError("Windows rollout health observation window is invalid or stale")
    manifest = read_canonical_manifest(
        candidate_root / "update/manifest.json", ended)
    identity = ("channel", "version", "sourceRevision", "manifestSequence")
    if (any(metrics.get(key) != completion.get(key) for key in identity)
            or any(metrics.get(key) != manifest.get(key) for key in identity)
            or metrics.get("releaseId") != completion.get("releaseId")
            or completion.get("releaseId") != _digest(
                candidate_root / "update/manifest.json")
            or metrics.get("rolloutPercentage") != manifest["rollout"]["percentage"]):
        raise ManifestError("Windows rollout health release identity does not match")
    counters = [metrics[key] for key in (
        "updateChecks", "eligibleDevices", "downloadStarted", "installSucceeded",
        "installFailed", "crashAffectedDevices")]
    if any(type(value) is not int or value < 0 for value in counters):
        raise ManifestError("Windows rollout health counters are invalid")
    checks, eligible, downloads, succeeded, failed, crashes = counters
    outcomes = succeeded + failed
    if not (checks >= eligible >= downloads >= outcomes >= succeeded >= crashes):
        raise ManifestError("Windows rollout health counters are inconsistent")
    current = metrics["rolloutPercentage"]
    steps = policy["rolloutSteps"]
    if type(current) is not int or current not in steps:
        raise ManifestError("Windows rollout percentage is outside the approved steps")
    failure_rate = _basis_points(failed, outcomes) if outcomes else 0
    crash_rate = _basis_points(crashes, succeeded) if succeeded else 0
    emergency = (
        (failed >= policy["emergencyInstallFailureCount"]
         and failure_rate > policy["maxInstallFailureBasisPoints"])
        or (crashes >= policy["emergencyCrashCount"]
            and crash_rate > policy["maxCrashBasisPoints"])
    )
    full_window = (ended - started).total_seconds() >= policy["minObservationSeconds"]
    enough_samples = outcomes >= policy["minInstallOutcomes"]
    within_limits = (failure_rate <= policy["maxInstallFailureBasisPoints"]
                     and crash_rate <= policy["maxCrashBasisPoints"])
    if emergency:
        decision, reason, next_percentage = "halt-recommended", "failure-threshold-exceeded", None
    elif current == 100:
        decision, reason, next_percentage = "complete", "rollout-already-complete", None
    elif full_window and enough_samples and within_limits:
        decision, reason = "expand-eligible", "minimum-health-gate-passed"
        next_percentage = steps[steps.index(current) + 1]
    else:
        decision, reason, next_percentage = "hold", "insufficient-or-inconclusive-evidence", None
    return {
        "schemaVersion": 1,
        "evidenceType": "windows-update-rollout-health-decision",
        "status": "advisory-no-channel-mutation",
        "decision": decision,
        "reason": reason,
        "channel": channel,
        "releaseId": completion["releaseId"],
        "version": completion["version"],
        "sourceRevision": completion["sourceRevision"],
        "manifestSequence": completion["manifestSequence"],
        "currentRolloutPercentage": current,
        "nextRolloutPercentage": next_percentage,
        "installFailureBasisPoints": failure_rate,
        "crashBasisPoints": crash_rate,
        "installOutcomes": outcomes,
        "windowStartedAt": metrics["windowStartedAt"],
        "windowEndedAt": metrics["windowEndedAt"],
        "recordedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "completionSha256": _digest(completion_path),
        "metricsSha256": _digest(metrics_path),
        "policySha256": _digest(policy_path),
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if path.exists() or path.is_symlink() or not path.is_absolute():
        raise ManifestError("Windows rollout health output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Windows rollout health output directory is unsafe")
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", newline="\n", dir=path.parent, delete=False,
        ) as stream:
            stream.write(json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
            temporary = Path(stream.name)
        try:
            os.link(temporary, path)
        except FileExistsError as error:
            raise ManifestError("Windows rollout health output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify(path: Path, completion: Path, candidate: Path, metrics: Path,
           policy: Path) -> dict[str, object]:
    recorded = _read(path, "decision")
    if set(recorded) != RESULT_KEYS:
        raise ManifestError("Windows rollout health decision shape is invalid")
    expected = evaluate(
        completion, candidate, metrics, policy,
        _time(recorded.get("recordedAt"), "decision time"))
    if recorded != expected:
        raise ManifestError("Windows rollout health decision does not match inputs")
    return recorded


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("record", "verify"))
    parser.add_argument("--completion", type=Path, required=True)
    parser.add_argument("--candidate-root", type=Path, required=True)
    parser.add_argument("--metrics", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "record":
            value = evaluate(
                args.completion, args.candidate_root, args.metrics, args.policy,
                datetime.now(timezone.utc).replace(microsecond=0))
            write_once(args.output.resolve(strict=False), value)
        else:
            value = verify(
                args.output, args.completion, args.candidate_root,
                args.metrics, args.policy)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows rollout health failed: {error}") from None
    print(json.dumps(value, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
