#!/usr/bin/env python3
"""Observe a Windows forward fix and resolve its open rollout incident."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError
from windows_update_forward_fix_authorization import (
    add_authorization_arguments, authorization_values,
)
from windows_update_forward_fix_execution import verify_execution
from windows_update_incident_state import inspect_open_incident, resolve_incident
from windows_update_release_completion import write_once
from windows_update_release_execution import _digest, _read_json, inspect_active
from windows_update_release_probe import read_observation


STATUS = "production-forward-fix-observed"
KEYS = {
    "schemaVersion", "evidenceType", "status", "channel", "incidentId",
    "failedReleaseId", "restoredReleaseId", "releaseId", "manifestSequence",
    "version", "sourceRevision", "manifestUrl", "executionSha256",
    "observationSha256", "executedAt", "observedAt", "completedAt",
    "maximumCompletionSeconds",
}


def _time(value: object, label: str) -> datetime:
    try:
        return datetime.strptime(str(value), "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError(f"Windows forward-fix completion {label} is invalid") from error


def build_completion(
    execution_path: Path,
    authorization_path: Path,
    authorization_inputs: tuple[object, ...],
    observation_path: Path,
    store_root: Path,
    now_utc: datetime,
    maximum_completion_seconds: int = 600,
    require_open_incident: bool = True,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows forward-fix completion clock must be exact UTC")
    if not 60 <= maximum_completion_seconds <= 900:
        raise ManifestError("Windows forward-fix completion window must be 60 to 900 seconds")
    execution = verify_execution(
        execution_path, authorization_path, authorization_inputs)
    target_root = authorization_inputs[2]
    observation = read_observation(observation_path, target_root)
    active = inspect_active(store_root, now_utc)
    incident = inspect_open_incident(store_root, now_utc)
    if ((require_open_incident
         and (incident is None or incident["incidentId"] != execution["incidentId"]))
            or active["releaseId"] != execution["releaseId"]
            or observation["manifestSha256"] != execution["releaseId"]
            or observation["manifestSequence"] != execution["manifestSequence"]
            or observation["version"] != execution["version"]
            or observation["sourceRevision"] != execution["sourceRevision"]
            or observation["channel"] != execution["channel"]):
        raise ManifestError("Observed Windows forward fix differs from execution")
    executed = _time(execution["executedAt"], "execution time")
    observed = _time(observation["observedAt"], "observation time")
    deadline = executed + timedelta(seconds=maximum_completion_seconds)
    if (observed < executed or observed > deadline or now_utc > deadline
            or observed > now_utc + timedelta(minutes=1)):
        raise ManifestError("Windows forward-fix observation is outside completion window")
    return {
        "schemaVersion": 1,
        "evidenceType": "windows-update-forward-fix-completion",
        "status": STATUS,
        "channel": execution["channel"],
        "incidentId": execution["incidentId"],
        "failedReleaseId": execution["failedReleaseId"],
        "restoredReleaseId": execution["restoredReleaseId"],
        "releaseId": execution["releaseId"],
        "manifestSequence": execution["manifestSequence"],
        "version": execution["version"],
        "sourceRevision": execution["sourceRevision"],
        "manifestUrl": observation["manifestUrl"],
        "executionSha256": _digest(execution_path),
        "observationSha256": _digest(observation_path),
        "executedAt": execution["executedAt"],
        "observedAt": observation["observedAt"],
        "completedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "maximumCompletionSeconds": maximum_completion_seconds,
    }


def verify_completion(
    path: Path,
    execution_path: Path,
    authorization_path: Path,
    authorization_inputs: tuple[object, ...],
    observation_path: Path,
    store_root: Path,
) -> dict[str, object]:
    value = _read_json(path, "Windows forward-fix completion")
    if set(value) != KEYS or type(value.get("maximumCompletionSeconds")) is not int:
        raise ManifestError("Windows forward-fix completion shape is invalid")
    completed = _time(value.get("completedAt"), "completion time")
    expected = build_completion(
        execution_path, authorization_path, authorization_inputs,
        observation_path, store_root, completed,
        int(value["maximumCompletionSeconds"]), False)
    if value != expected:
        raise ManifestError("Windows forward-fix completion differs from inputs")
    resolved_path = (
        store_root / ".resolved-rollout-incidents"
        / f"{value['incidentId']}.json")
    resolved = _read_json(resolved_path, "Resolved Windows rollout incident")
    if (resolved.get("status") != "resolved-by-observed-forward-fix"
            or resolved.get("incidentId") != value["incidentId"]
            or resolved.get("forwardFixReleaseId") != value["releaseId"]
            or resolved.get("forwardFixExecutionSha256") != value["executionSha256"]
            or resolved.get("recoveryCompletionSha256") != _digest(path)):
        raise ManifestError("Resolved Windows rollout incident differs from completion")
    return value


def complete(
    output_path: Path,
    execution_path: Path,
    authorization_path: Path,
    authorization_inputs: tuple[object, ...],
    observation_path: Path,
    store_root: Path,
    now_utc: datetime,
    maximum_completion_seconds: int = 600,
) -> dict[str, object]:
    if output_path.exists() or output_path.is_symlink():
        value = _read_json(output_path, "Windows forward-fix completion")
        if set(value) != KEYS or type(value.get("maximumCompletionSeconds")) is not int:
            raise ManifestError("Windows forward-fix completion shape is invalid")
        completed = _time(value["completedAt"], "completion time")
        expected = build_completion(
            execution_path, authorization_path, authorization_inputs,
            observation_path, store_root, completed,
            int(value["maximumCompletionSeconds"]))
        if value != expected:
            raise ManifestError("Existing Windows forward-fix completion differs")
    else:
        value = build_completion(
            execution_path, authorization_path, authorization_inputs,
            observation_path, store_root, now_utc, maximum_completion_seconds)
        write_once(output_path, value)
    resolve_incident(
        store_root, str(value["incidentId"]), str(value["releaseId"]),
        str(value["executionSha256"]), _digest(output_path), now_utc)
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("complete", "verify"))
    parser.add_argument("--execution", type=Path, required=True)
    parser.add_argument("--authorization", type=Path, required=True)
    add_authorization_arguments(parser)
    parser.add_argument("--observation", type=Path, required=True)
    parser.add_argument("--store-root", type=Path, required=True)
    parser.add_argument("--maximum-completion-seconds", type=int, default=600)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    values = authorization_values(args)
    try:
        if args.command == "complete":
            result = complete(
                args.output, args.execution, args.authorization, values,
                args.observation, args.store_root,
                datetime.now(timezone.utc).replace(microsecond=0),
                args.maximum_completion_seconds)
        else:
            result = verify_completion(
                args.output, args.execution, args.authorization, values,
                args.observation, args.store_root)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows forward-fix completion failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
