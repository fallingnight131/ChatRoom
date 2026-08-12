#!/usr/bin/env python3
"""Bind post-switch Windows update HTTPS observation into completion evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError
from windows_update_release_execution import verify_execution
from windows_update_release_probe import read_observation


KEYS = {
    "schemaVersion", "evidenceType", "status", "channel", "releaseId",
    "rollbackReleaseId", "version", "sourceRevision", "manifestSequence",
    "manifestUrl", "executionSha256", "observationSha256", "executedAt",
    "observedAt", "completedAt", "maximumCompletionSeconds",
}


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Windows update completion input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _time(value: object, label: str) -> datetime:
    try:
        return datetime.strptime(str(value), "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError(f"Windows update completion {label} is invalid") from error


def build_completion(
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
    observation_path: Path,
    now_utc: datetime,
    maximum_completion_seconds: int = 600,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows update completion clock must be an exact UTC second")
    if not 60 <= maximum_completion_seconds <= 900:
        raise ManifestError("Windows update completion window must be 60 to 900 seconds")
    execution = verify_execution(
        execution_path, authorization_path, candidate_root, rollback_release_root,
        current_manifest_path, version_file, source_revision, channel, qt_version,
        authenticode_signer_sha256, public_key_file_sha256,
    )
    observation = read_observation(observation_path, candidate_root)
    if (observation["channel"] != execution["channel"]
            or observation["version"] != execution["version"]
            or observation["sourceRevision"] != execution["sourceRevision"]
            or observation["manifestSequence"] != execution["manifestSequence"]
            or observation["manifestSha256"] != execution["releaseId"]):
        raise ManifestError("Windows update post-switch observation does not match execution")
    executed = _time(execution["executedAt"], "execution time")
    observed = _time(observation["observedAt"], "observation time")
    deadline = executed + timedelta(seconds=maximum_completion_seconds)
    if (observed < executed or observed > deadline or now_utc > deadline
            or observed > now_utc + timedelta(minutes=1)):
        raise ManifestError("Windows update observation is outside completion window")
    return {
        "schemaVersion": 1,
        "evidenceType": "windows-update-production-promotion-completion",
        "status": "production-update-promotion-observed",
        "channel": channel,
        "releaseId": execution["releaseId"],
        "rollbackReleaseId": execution["rollbackReleaseId"],
        "version": execution["version"],
        "sourceRevision": execution["sourceRevision"],
        "manifestSequence": execution["manifestSequence"],
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
        raise ManifestError("Windows update completion output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Windows update completion output directory is unsafe")
    rendered = json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n"
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", newline="\n", dir=path.parent, delete=False,
        ) as stream:
            stream.write(rendered)
            stream.flush()
            os.fsync(stream.fileno())
            temporary = Path(stream.name)
        try:
            os.link(temporary, path)
        except FileExistsError as error:
            raise ManifestError("Windows update completion output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_completion(
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
    observation_path: Path,
) -> dict[str, object]:
    if completion_path.is_symlink() or not completion_path.is_file():
        raise ManifestError("Windows update completion evidence must be a regular file")

    def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Windows update completion evidence has duplicate keys")
            result[key] = value
        return result

    try:
        recorded = json.loads(
            completion_path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Windows update completion evidence is unreadable") from error
    if not isinstance(recorded, dict) or set(recorded) != KEYS:
        raise ManifestError("Windows update completion evidence has an unsupported shape")
    maximum = recorded.get("maximumCompletionSeconds")
    if type(maximum) is not int:
        raise ManifestError("Windows update completion window is malformed")
    completed = _time(recorded.get("completedAt"), "completion time")
    expected = build_completion(
        execution_path, authorization_path, candidate_root, rollback_release_root,
        current_manifest_path, version_file, source_revision, channel, qt_version,
        authenticode_signer_sha256, public_key_file_sha256, observation_path,
        completed, maximum,
    )
    if recorded != expected:
        raise ManifestError("Windows update completion evidence does not match its inputs")
    return recorded


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("record", "verify"))
    for name in (
        "completion", "execution", "authorization", "candidate-root",
        "rollback-release-root", "current-manifest", "version-file", "observation",
    ):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    parser.add_argument("--qt-version", required=True)
    parser.add_argument("--authenticode-signer-sha256", required=True)
    parser.add_argument("--public-key-file-sha256", required=True)
    parser.add_argument("--maximum-completion-seconds", type=int, default=600)
    args = parser.parse_args()
    now = datetime.now(timezone.utc).replace(microsecond=0)
    values = (
        args.execution, args.authorization, args.candidate_root,
        args.rollback_release_root, args.current_manifest, args.version_file,
        args.source_revision, args.channel, args.qt_version,
        args.authenticode_signer_sha256, args.public_key_file_sha256,
        args.observation,
    )
    try:
        if args.command == "record":
            value = build_completion(*values, now, args.maximum_completion_seconds)
            write_once(args.completion, value)
        else:
            value = verify_completion(args.completion, *values)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows update completion failed: {error}") from None
    print(json.dumps(value, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
