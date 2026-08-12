#!/usr/bin/env python3
"""Authorize a higher-version Windows forward fix after an observed rollout halt."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError
from windows_update_channel_candidate import (
    _require_client_trusted_update_key, validate_candidate,
)
from windows_update_manifest import _parse_version, verify_manifest_signature
from windows_update_rollback_completion import (
    add_completion_arguments, completion_values, verify_completion,
)


STATUS = "forward-fix-approved-not-executed"
ENVIRONMENT = "windows-update-production"
HEX64 = re.compile(r"^[0-9a-f]{64}$")
KEYS = {
    "schemaVersion", "authorizationType", "status", "environment", "channel",
    "failedReleaseId", "restoredReleaseId", "targetReleaseId",
    "failedVersion", "targetVersion", "targetSourceRevision",
    "failedManifestSequence", "restoredManifestSequence",
    "targetManifestSequence", "targetRolloutPercentage",
    "targetMinimumUpdatableVersion", "targetSigningKeyId",
    "targetCandidateManifestSha256", "targetManifestSha256",
    "rollbackCompletionSha256", "expectedAuthenticodeSignerSha256",
    "targetUpdatePublicKeyFileSha256", "approvedAt", "expiresAt",
}


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Windows forward-fix input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _read(path: Path, label: str) -> dict[str, object]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ManifestError(f"Windows forward-fix {label} is unsafe")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError(f"Windows forward-fix {label} has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError(f"Windows forward-fix {label} is unreadable") from error
    if not isinstance(value, dict):
        raise ManifestError(f"Windows forward-fix {label} must be an object")
    return value


def _time(value: object, label: str) -> datetime:
    try:
        return datetime.strptime(str(value), "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError(f"Windows forward-fix {label} is invalid") from error


def create_authorization(
    rollback_completion_path: Path,
    rollback_completion_inputs: tuple[object, ...],
    target_candidate_root: Path,
    target_version_file: Path,
    target_source_revision: str,
    target_qt_version: str,
    authenticode_signer_sha256: str,
    target_update_public_key_sha256: str,
    now_utc: datetime,
    lifetime_seconds: int = 900,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows forward-fix clock must be an exact UTC second")
    if not 60 <= lifetime_seconds <= 900:
        raise ManifestError("Windows forward-fix lifetime must be 60 to 900 seconds")
    if not HEX64.fullmatch(target_update_public_key_sha256):
        raise ManifestError("Windows forward-fix update public key SHA-256 is invalid")
    halted = verify_completion(
        rollback_completion_path, *rollback_completion_inputs)
    channel = str(halted["channel"])
    failed_root = rollback_completion_inputs[4]
    restored_root = rollback_completion_inputs[5]
    failed_update = verify_manifest_signature(
        failed_root / "update/manifest.json",
        failed_root / "update/manifest.json.sig",
        failed_root / "evidence/update-public-key.pem", now_utc)
    restored_update = verify_manifest_signature(
        restored_root / "update/manifest.json",
        restored_root / "update/manifest.json.sig",
        restored_root / "evidence/update-public-key.pem", now_utc)
    target_identity = validate_candidate(
        target_candidate_root, target_version_file, target_source_revision,
        channel, target_qt_version, authenticode_signer_sha256,
        target_update_public_key_sha256, now_utc)
    target_outer = _read(
        target_candidate_root / "windows-update-channel-candidate.json",
        "target candidate")
    assembled = _time(target_outer.get("assembledAt"), "target assembly time")
    if assembled > now_utc or now_utc - assembled > timedelta(hours=24):
        raise ManifestError("Windows forward-fix target is stale or from the future")
    target_public = target_candidate_root / "evidence/update-public-key.pem"
    target_update = verify_manifest_signature(
        target_candidate_root / "update/manifest.json",
        target_candidate_root / "update/manifest.json.sig",
        target_public, now_utc)
    _require_client_trusted_update_key(
        failed_root / "windows", str(target_update["signingKeyId"]), target_public)
    failed_version = _parse_version(failed_update["version"], "failed version")
    target_version = _parse_version(target_update["version"], "target version")
    minimum = _parse_version(
        target_update["minimumUpdatableVersion"], "target minimum version")
    if (halted["failedReleaseId"] != _digest(failed_root / "update/manifest.json")
            or halted["restoredReleaseId"] != _digest(restored_root / "update/manifest.json")
            or restored_update["manifestSequence"] != halted["restoredManifestSequence"]
            or target_identity["version"] != target_update["version"]
            or target_version <= failed_version
            or target_update["sourceRevision"] == failed_update["sourceRevision"]
            or target_update["manifestSequence"] <= failed_update["manifestSequence"]
            or target_update["rollout"]["percentage"] != 100
            or minimum > failed_version):
        raise ManifestError("Windows forward-fix target does not repair the failed release")
    return {
        "schemaVersion": 1,
        "authorizationType": "windows-update-forward-fix",
        "status": STATUS,
        "environment": ENVIRONMENT,
        "channel": channel,
        "failedReleaseId": halted["failedReleaseId"],
        "restoredReleaseId": halted["restoredReleaseId"],
        "targetReleaseId": _digest(target_candidate_root / "update/manifest.json"),
        "failedVersion": failed_update["version"],
        "targetVersion": target_update["version"],
        "targetSourceRevision": target_source_revision,
        "failedManifestSequence": failed_update["manifestSequence"],
        "restoredManifestSequence": restored_update["manifestSequence"],
        "targetManifestSequence": target_update["manifestSequence"],
        "targetRolloutPercentage": 100,
        "targetMinimumUpdatableVersion": target_update["minimumUpdatableVersion"],
        "targetSigningKeyId": target_update["signingKeyId"],
        "targetCandidateManifestSha256": _digest(
            target_candidate_root / "windows-update-channel-candidate.json"),
        "targetManifestSha256": _digest(target_candidate_root / "update/manifest.json"),
        "rollbackCompletionSha256": _digest(rollback_completion_path),
        "expectedAuthenticodeSignerSha256": authenticode_signer_sha256,
        "targetUpdatePublicKeyFileSha256": target_update_public_key_sha256,
        "approvedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "expiresAt": (now_utc + timedelta(seconds=lifetime_seconds)).strftime(
            "%Y-%m-%dT%H:%M:%SZ"),
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if path.exists() or path.is_symlink() or not path.is_absolute():
        raise ManifestError("Windows forward-fix output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Windows forward-fix output directory is unsafe")
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
            raise ManifestError("Windows forward-fix output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_authorization(
    path: Path,
    rollback_completion_path: Path,
    rollback_completion_inputs: tuple[object, ...],
    target_candidate_root: Path,
    target_version_file: Path,
    target_source_revision: str,
    target_qt_version: str,
    authenticode_signer_sha256: str,
    target_update_public_key_sha256: str,
    now_utc: datetime,
) -> dict[str, object]:
    value = _read(path, "authorization")
    if set(value) != KEYS:
        raise ManifestError("Windows forward-fix authorization shape is invalid")
    approved = _time(value.get("approvedAt"), "approval time")
    expires = _time(value.get("expiresAt"), "expiry time")
    lifetime = int((expires - approved).total_seconds())
    if (lifetime < 60 or lifetime > 900 or now_utc.tzinfo != timezone.utc
            or now_utc.microsecond or approved > now_utc + timedelta(minutes=1)
            or now_utc >= expires):
        raise ManifestError("Windows forward-fix authorization is expired or future")
    expected = create_authorization(
        rollback_completion_path, rollback_completion_inputs,
        target_candidate_root, target_version_file, target_source_revision,
        target_qt_version, authenticode_signer_sha256,
        target_update_public_key_sha256, approved, lifetime)
    if value != expected:
        raise ManifestError("Windows forward-fix authorization differs from inputs")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("create", "verify"))
    parser.add_argument("--rollback-completion", type=Path, required=True)
    add_completion_arguments(parser)
    parser.add_argument("--target-candidate-root", type=Path, required=True)
    parser.add_argument("--target-version-file", type=Path, required=True)
    parser.add_argument("--target-source-revision", required=True)
    parser.add_argument("--target-qt-version", required=True)
    parser.add_argument("--target-authenticode-signer-sha256", required=True)
    parser.add_argument("--target-update-public-key-sha256", required=True)
    parser.add_argument("--lifetime-seconds", type=int, default=900)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    values = (
        args.rollback_completion, completion_values(args),
        args.target_candidate_root, args.target_version_file,
        args.target_source_revision, args.target_qt_version,
        args.target_authenticode_signer_sha256,
        args.target_update_public_key_sha256,
    )
    now = datetime.now(timezone.utc).replace(microsecond=0)
    try:
        if args.command == "create":
            result = create_authorization(*values, now, args.lifetime_seconds)
            write_once(args.output.resolve(strict=False), result)
        else:
            result = verify_authorization(*((args.output,) + values + (now,)))
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows forward-fix authorization failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
