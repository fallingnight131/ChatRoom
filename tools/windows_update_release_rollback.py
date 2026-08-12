#!/usr/bin/env python3
"""Restore the exact prior Windows update release derived from completion evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError, atomic_write
from windows_update_channel_store import validate_release, validate_release_from_candidate
from windows_update_release_completion import verify_completion
from windows_update_release_execution import inspect_active
from windows_update_manifest import verify_manifest_signature


STATUS = "rollback-pointer-restored-awaiting-external-observation"
KEYS = {
    "schemaVersion", "evidenceType", "status", "adapter", "channel",
    "failedReleaseId", "restoredReleaseId", "failedManifestSequence",
    "restoredManifestSequence", "restoredVersion", "restoredSourceRevision",
    "completionSha256", "executionSha256", "rolledBackAt",
}


def _require_live_manifest(release_root: Path, observed_at: datetime) -> None:
    verify_manifest_signature(
        release_root / "update/manifest.json",
        release_root / "update/manifest.json.sig",
        release_root / "evidence/update-public-key.pem",
        observed_at,
    )


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Windows update rollback input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_once(path: Path, value: dict[str, object], label: str) -> None:
    if path.exists() or path.is_symlink() or not path.is_absolute():
        raise ManifestError(f"{label} is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError(f"{label} directory is unsafe")
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
            raise ManifestError(f"{label} already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _completion(
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
    return verify_completion(
        completion_path, execution_path, authorization_path, candidate_root,
        rollback_release_root, current_manifest_path, version_file,
        source_revision, channel, qt_version, authenticode_signer_sha256,
        public_key_file_sha256, observation_path,
    )


def execute_rollback(
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
    store_root: Path,
    evidence_path: Path,
    now_utc: datetime,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows update rollback clock must be an exact UTC second")
    completion = _completion(
        completion_path, execution_path, authorization_path, candidate_root,
        rollback_release_root, current_manifest_path, version_file,
        source_revision, channel, qt_version, authenticode_signer_sha256,
        public_key_file_sha256, observation_path,
    )
    if (not store_root.is_absolute() or store_root.is_symlink() or not store_root.is_dir()
            or (store_root / "releases").is_symlink()
            or (store_root / "active-channel.json").is_symlink()):
        raise ManifestError("Windows update rollback store boundary is unsafe")
    failed = validate_release(
        candidate_root, version_file, source_revision, channel, qt_version,
        authenticode_signer_sha256, public_key_file_sha256, now_utc,
    )
    restored = validate_release_from_candidate(rollback_release_root, now_utc)
    _require_live_manifest(rollback_release_root, now_utc)
    expected_failed = store_root / "releases" / str(completion["releaseId"])
    expected_restored = store_root / "releases" / str(completion["rollbackReleaseId"])
    if (candidate_root.resolve() != expected_failed.resolve()
            or rollback_release_root.resolve() != expected_restored.resolve()
            or failed["releaseId"] != completion["releaseId"]
            or restored["releaseId"] != completion["rollbackReleaseId"]):
        raise ManifestError("Windows update rollback releases are outside authorized store")
    active = inspect_active(store_root, now_utc)
    if active["releaseId"] != failed["releaseId"]:
        raise ManifestError("Windows update rollback active pointer is not the failed release")

    completion_digest = _digest(completion_path)
    consumption = store_root / ".rollback-consumptions" / f"{completion_digest}.json"
    _write_once(consumption, {
        "schemaVersion": 1,
        "status": "consumed-before-rollback",
        "completionSha256": completion_digest,
        "failedReleaseId": failed["releaseId"],
        "restoredReleaseId": restored["releaseId"],
        "consumedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }, "Windows update rollback consumption marker")

    rolled_back_at = now_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
    pointer = {
        "schemaVersion": 1,
        "channel": restored["channel"],
        "releaseId": restored["releaseId"],
        "manifestSequence": restored["manifestSequence"],
        "version": restored["version"],
        "sourceRevision": restored["sourceRevision"],
        "activatedAt": rolled_back_at,
    }
    atomic_write(
        store_root / "active-channel.json",
        json.dumps(pointer, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
    )
    after = inspect_active(store_root, now_utc)
    if after["releaseId"] != restored["releaseId"]:
        raise ManifestError("Windows update rollback pointer did not restore prior release")
    evidence = {
        "schemaVersion": 1,
        "evidenceType": "windows-update-channel-rollback-execution",
        "status": STATUS,
        "adapter": "atomic-filesystem-update-pointer",
        "channel": channel,
        "failedReleaseId": failed["releaseId"],
        "restoredReleaseId": restored["releaseId"],
        "failedManifestSequence": failed["manifestSequence"],
        "restoredManifestSequence": restored["manifestSequence"],
        "restoredVersion": restored["version"],
        "restoredSourceRevision": restored["sourceRevision"],
        "completionSha256": completion_digest,
        "executionSha256": _digest(execution_path),
        "rolledBackAt": rolled_back_at,
    }
    _write_once(evidence_path, evidence, "Windows update rollback evidence")
    return evidence


def verify_rollback(
    evidence_path: Path,
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
    if evidence_path.is_symlink() or not evidence_path.is_file():
        raise ManifestError("Windows update rollback evidence must be a regular file")

    def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Windows update rollback evidence has duplicate keys")
            result[key] = value
        return result

    try:
        recorded = json.loads(
            evidence_path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Windows update rollback evidence is unreadable") from error
    if not isinstance(recorded, dict) or set(recorded) != KEYS:
        raise ManifestError("Windows update rollback evidence has an unsupported shape")
    try:
        rolled_back = datetime.strptime(
            str(recorded["rolledBackAt"]), "%Y-%m-%dT%H:%M:%SZ").replace(
                tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError("Windows update rollback time is invalid") from error
    completion = _completion(
        completion_path, execution_path, authorization_path, candidate_root,
        rollback_release_root, current_manifest_path, version_file,
        source_revision, channel, qt_version, authenticode_signer_sha256,
        public_key_file_sha256, observation_path,
    )
    failed = validate_release(
        candidate_root, version_file, source_revision, channel, qt_version,
        authenticode_signer_sha256, public_key_file_sha256, rolled_back,
    )
    restored = validate_release_from_candidate(rollback_release_root, rolled_back)
    _require_live_manifest(rollback_release_root, rolled_back)
    expected = {
        "schemaVersion": 1,
        "evidenceType": "windows-update-channel-rollback-execution",
        "status": STATUS,
        "adapter": "atomic-filesystem-update-pointer",
        "channel": channel,
        "failedReleaseId": failed["releaseId"],
        "restoredReleaseId": restored["releaseId"],
        "failedManifestSequence": failed["manifestSequence"],
        "restoredManifestSequence": restored["manifestSequence"],
        "restoredVersion": restored["version"],
        "restoredSourceRevision": restored["sourceRevision"],
        "completionSha256": _digest(completion_path),
        "executionSha256": _digest(execution_path),
        "rolledBackAt": recorded["rolledBackAt"],
    }
    if (recorded != expected or completion["releaseId"] != failed["releaseId"]
            or completion["rollbackReleaseId"] != restored["releaseId"]):
        raise ManifestError("Windows update rollback evidence does not match completion")
    return recorded


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("execute", "verify"))
    for name in (
        "rollback-evidence", "completion", "execution", "authorization",
        "candidate-root", "rollback-release-root", "current-manifest",
        "version-file", "observation",
    ):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    parser.add_argument("--qt-version", required=True)
    parser.add_argument("--authenticode-signer-sha256", required=True)
    parser.add_argument("--public-key-file-sha256", required=True)
    parser.add_argument("--store-root", type=Path)
    args = parser.parse_args()
    values = (
        args.completion, args.execution, args.authorization, args.candidate_root,
        args.rollback_release_root, args.current_manifest, args.version_file,
        args.source_revision, args.channel, args.qt_version,
        args.authenticode_signer_sha256, args.public_key_file_sha256,
        args.observation,
    )
    now = datetime.now(timezone.utc).replace(microsecond=0)
    try:
        if args.command == "execute":
            if args.store_root is None:
                raise ManifestError("Windows update rollback store root is required")
            result = execute_rollback(
                *values, args.store_root, args.rollback_evidence, now)
        else:
            result = verify_rollback(args.rollback_evidence, *values)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows update rollback failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
