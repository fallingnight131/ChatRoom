#!/usr/bin/env python3
"""Bind public HTTPS observation to one Windows rollout expansion execution."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError
from windows_update_release_probe import read_observation
from windows_update_rollout_expansion_authorization import (
    add_authorization_arguments, authorization_values,
)
from windows_update_rollout_expansion_execution import verify_execution


STATUS = "production-rollout-expansion-observed"
KEYS = {
    "schemaVersion", "evidenceType", "status", "channel", "releaseId",
    "rollbackReleaseId", "version", "sourceRevision", "manifestSequence",
    "currentRolloutPercentage", "targetRolloutPercentage", "rolloutSeed",
    "manifestUrl", "executionSha256", "observationSha256", "executedAt",
    "observedAt", "completedAt", "maximumCompletionSeconds",
}


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Windows rollout expansion completion input is unsafe")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _time(value: object, label: str) -> datetime:
    try:
        return datetime.strptime(str(value), "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError(f"Windows rollout expansion completion {label} is invalid") from error


def build_completion(
    execution_path: Path,
    authorization_path: Path,
    authorization_inputs: tuple[object, ...],
    observation_path: Path,
    now_utc: datetime,
    maximum_completion_seconds: int = 600,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows rollout expansion completion clock must be exact UTC")
    if not 60 <= maximum_completion_seconds <= 900:
        raise ManifestError("Windows rollout expansion completion window must be 60 to 900 seconds")
    execution = verify_execution(
        execution_path, authorization_path, authorization_inputs)
    target_root = authorization_inputs[14]
    observation = read_observation(observation_path, target_root)
    if (observation["channel"] != execution["channel"]
            or observation["version"] != execution["version"]
            or observation["sourceRevision"] != execution["sourceRevision"]
            or observation["manifestSequence"] != execution["manifestSequence"]
            or observation["manifestSha256"] != execution["releaseId"]):
        raise ManifestError("Windows rollout expansion observation identity differs")
    executed = _time(execution["executedAt"], "execution time")
    observed = _time(observation["observedAt"], "observation time")
    deadline = executed + timedelta(seconds=maximum_completion_seconds)
    if (observed < executed or observed > deadline or now_utc > deadline
            or observed > now_utc + timedelta(minutes=1)):
        raise ManifestError("Windows rollout expansion observation is outside completion window")
    return {
        "schemaVersion": 1,
        "evidenceType": "windows-update-rollout-expansion-completion",
        "status": STATUS,
        "channel": execution["channel"],
        "releaseId": execution["releaseId"],
        "rollbackReleaseId": execution["rollbackReleaseId"],
        "version": execution["version"],
        "sourceRevision": execution["sourceRevision"],
        "manifestSequence": execution["manifestSequence"],
        "currentRolloutPercentage": execution["currentRolloutPercentage"],
        "targetRolloutPercentage": execution["targetRolloutPercentage"],
        "rolloutSeed": execution["rolloutSeed"],
        "manifestUrl": observation["manifestUrl"],
        "executionSha256": _digest(execution_path),
        "observationSha256": _digest(observation_path),
        "executedAt": execution["executedAt"],
        "observedAt": observation["observedAt"],
        "completedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "maximumCompletionSeconds": maximum_completion_seconds,
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if path.exists() or path.is_symlink() or not path.is_absolute():
        raise ManifestError(
            "Windows rollout expansion completion output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Windows rollout expansion completion directory is unsafe")
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
            raise ManifestError("Windows rollout expansion completion already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_completion(
    completion_path: Path,
    execution_path: Path,
    authorization_path: Path,
    authorization_inputs: tuple[object, ...],
    observation_path: Path,
) -> dict[str, object]:
    if completion_path.is_symlink() or not completion_path.is_file():
        raise ManifestError("Windows rollout expansion completion must be a regular file")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Windows rollout expansion completion has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(
            completion_path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Windows rollout expansion completion is unreadable") from error
    if not isinstance(value, dict) or set(value) != KEYS:
        raise ManifestError("Windows rollout expansion completion shape is invalid")
    maximum = value.get("maximumCompletionSeconds")
    if type(maximum) is not int:
        raise ManifestError("Windows rollout expansion completion window is invalid")
    expected = build_completion(
        execution_path, authorization_path, authorization_inputs,
        observation_path, _time(value.get("completedAt"), "completion time"),
        maximum)
    if value != expected:
        raise ManifestError("Windows rollout expansion completion differs from inputs")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("record", "verify"))
    parser.add_argument("--expansion-execution", type=Path, required=True)
    parser.add_argument("--expansion-authorization", type=Path, required=True)
    parser.add_argument("--observation", type=Path, required=True)
    add_authorization_arguments(parser)
    parser.add_argument("--maximum-completion-seconds", type=int, default=600)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    values = authorization_values(args)
    try:
        if args.command == "record":
            result = build_completion(
                args.expansion_execution, args.expansion_authorization, values,
                args.observation, datetime.now(timezone.utc).replace(microsecond=0),
                args.maximum_completion_seconds)
            write_once(args.output.resolve(strict=False), result)
        else:
            result = verify_completion(
                args.output, args.expansion_execution,
                args.expansion_authorization, values, args.observation)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows rollout expansion completion failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
