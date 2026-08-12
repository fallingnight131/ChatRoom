#!/usr/bin/env python3
"""Bind restored-release HTTPS observation to Windows rollout-halt evidence."""

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
from windows_update_release_rollback import verify_rollback


KEYS = {
    "schemaVersion", "evidenceType", "status", "channel", "failedReleaseId",
    "restoredReleaseId", "restoredManifestSequence", "restoredVersion",
    "restoredSourceRevision", "manifestUrl", "rollbackExecutionSha256",
    "restoredObservationSha256", "rolledBackAt", "observedAt", "completedAt",
    "maximumCompletionSeconds",
}


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Windows rollout-halt completion input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _time(value: object, label: str) -> datetime:
    try:
        return datetime.strptime(str(value), "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError(f"Windows rollout-halt completion {label} is invalid") from error


def build_completion(
    rollback_evidence_path: Path,
    completion_path: Path,
    execution_path: Path,
    authorization_path: Path,
    candidate_root: Path,
    rollback_release_root: Path,
    current_manifest_path: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    authenticode_signer_sha256: str,
    public_key_file_sha256: str,
    promotion_observation_path: Path,
    restored_observation_path: Path,
    now_utc: datetime,
    maximum_completion_seconds: int = 600,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows rollout-halt completion clock must be exact UTC")
    if not 60 <= maximum_completion_seconds <= 900:
        raise ManifestError("Windows rollout-halt completion window must be 60 to 900 seconds")
    rollback = verify_rollback(
        rollback_evidence_path, completion_path, execution_path,
        authorization_path, candidate_root, rollback_release_root,
        current_manifest_path, version_file, source_revision, channel, qt_version,
        authenticode_signer_sha256, public_key_file_sha256,
        promotion_observation_path,
    )
    observation = read_observation(restored_observation_path, rollback_release_root)
    if (observation["channel"] != rollback["channel"]
            or observation["manifestSha256"] != rollback["restoredReleaseId"]
            or observation["manifestSequence"] != rollback["restoredManifestSequence"]
            or observation["version"] != rollback["restoredVersion"]
            or observation["sourceRevision"] != rollback["restoredSourceRevision"]):
        raise ManifestError("Restored Windows update observation does not match rollback")
    rolled_back = _time(rollback["rolledBackAt"], "rollback time")
    observed = _time(observation["observedAt"], "observation time")
    deadline = rolled_back + timedelta(seconds=maximum_completion_seconds)
    if (observed < rolled_back or observed > deadline or now_utc > deadline
            or observed > now_utc + timedelta(minutes=1)):
        raise ManifestError("Restored observation is outside rollout-halt window")
    return {
        "schemaVersion": 1,
        "evidenceType": "windows-update-rollout-halt-completion",
        "status": "production-update-rollout-halt-observed",
        "channel": channel,
        "failedReleaseId": rollback["failedReleaseId"],
        "restoredReleaseId": rollback["restoredReleaseId"],
        "restoredManifestSequence": rollback["restoredManifestSequence"],
        "restoredVersion": rollback["restoredVersion"],
        "restoredSourceRevision": rollback["restoredSourceRevision"],
        "manifestUrl": observation["manifestUrl"],
        "rollbackExecutionSha256": _digest(rollback_evidence_path),
        "restoredObservationSha256": _digest(restored_observation_path),
        "rolledBackAt": rollback["rolledBackAt"],
        "observedAt": observation["observedAt"],
        "completedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "maximumCompletionSeconds": maximum_completion_seconds,
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if path.exists() or path.is_symlink() or not path.is_absolute():
        raise ManifestError("Windows rollout-halt completion output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Windows rollout-halt completion directory is unsafe")
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
            raise ManifestError("Windows rollout-halt completion output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_completion(record_path: Path, *inputs) -> dict[str, object]:
    if record_path.is_symlink() or not record_path.is_file():
        raise ManifestError("Windows rollout-halt completion must be a regular file")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Windows rollout-halt completion has duplicate keys")
            result[key] = value
        return result

    try:
        recorded = json.loads(record_path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Windows rollout-halt completion is unreadable") from error
    if not isinstance(recorded, dict) or set(recorded) != KEYS:
        raise ManifestError("Windows rollout-halt completion has an unsupported shape")
    maximum = recorded.get("maximumCompletionSeconds")
    if type(maximum) is not int:
        raise ManifestError("Windows rollout-halt completion window is malformed")
    completed = _time(recorded.get("completedAt"), "completion time")
    expected = build_completion(*inputs, completed, maximum)
    if recorded != expected:
        raise ManifestError("Windows rollout-halt completion does not match inputs")
    return recorded


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("record", "verify"))
    for name in (
        "output", "rollback-evidence", "promotion-completion", "execution",
        "authorization", "candidate-root", "rollback-release-root",
        "current-manifest", "version-file", "promotion-observation",
        "restored-observation",
    ):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    parser.add_argument("--qt-version", required=True)
    parser.add_argument("--authenticode-signer-sha256", required=True)
    parser.add_argument("--public-key-file-sha256", required=True)
    parser.add_argument("--maximum-completion-seconds", type=int, default=600)
    args = parser.parse_args()
    inputs = (
        args.rollback_evidence, args.promotion_completion, args.execution,
        args.authorization, args.candidate_root, args.rollback_release_root,
        args.current_manifest, args.version_file, args.source_revision,
        args.channel, args.qt_version, args.authenticode_signer_sha256,
        args.public_key_file_sha256, args.promotion_observation,
        args.restored_observation,
    )
    now = datetime.now(timezone.utc).replace(microsecond=0)
    try:
        if args.command == "record":
            result = build_completion(*inputs, now, args.maximum_completion_seconds)
            write_once(args.output, result)
        else:
            result = verify_completion(args.output, *inputs)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows rollout-halt completion failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
