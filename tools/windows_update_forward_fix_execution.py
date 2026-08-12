#!/usr/bin/env python3
"""Consume one incident-bound Windows forward-fix authorization."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError, atomic_write
from windows_update_channel_store import validate_release, validate_release_from_candidate
from windows_update_forward_fix_authorization import (
    add_authorization_arguments, authorization_values, verify_authorization,
)
from windows_update_incident_state import inspect_open_incident
from windows_update_release_execution import (
    POINTER_KEYS, _digest, _pointer, _read_json, _write_once, inspect_active,
)


STATUS = "forward-fix-pointer-switched-awaiting-external-observation"
EVIDENCE_KEYS = {
    "schemaVersion", "evidenceType", "status", "adapter", "channel",
    "incidentId", "failedReleaseId", "restoredReleaseId", "releaseId",
    "failedManifestSequence", "restoredManifestSequence", "manifestSequence",
    "version", "sourceRevision", "authorizationSha256", "executedAt",
}


def _unpack(values: tuple[object, ...]):
    if len(values) != 8 or not isinstance(values[1], tuple) or len(values[1]) != 15:
        raise ManifestError("Windows forward-fix execution inputs are incomplete")
    return values


def execute(
    authorization_path: Path,
    authorization_inputs: tuple[object, ...],
    store_root: Path,
    evidence_path: Path,
    now_utc: datetime,
) -> dict[str, object]:
    values = _unpack(authorization_inputs)
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows forward-fix execution clock must be exact UTC")
    authorization = verify_authorization(
        authorization_path, *values, now_utc)
    rollback_inputs = values[1]
    target_root, version_file, source_revision = values[2:5]
    qt_version, signer_sha256, update_public_key_sha256 = values[5:8]
    channel = str(authorization["channel"])
    if (not isinstance(target_root, Path) or not isinstance(version_file, Path)
            or not isinstance(store_root, Path) or not store_root.is_absolute()
            or store_root.is_symlink() or not store_root.is_dir()
            or (store_root / "releases").is_symlink()
            or (store_root / "active-channel.json").is_symlink()):
        raise ManifestError("Windows forward-fix execution store boundary is unsafe")
    incident = inspect_open_incident(store_root, now_utc)
    promotion_completion_path = rollback_inputs[1]
    if (incident is None
            or incident["channel"] != channel
            or incident["failedReleaseId"] != authorization["failedReleaseId"]
            or incident["restoredReleaseId"] != authorization["restoredReleaseId"]
            or incident["promotionCompletionSha256"]
                != _digest(promotion_completion_path)):
        raise ManifestError("Open Windows rollout incident differs from forward fix")
    target_id = str(authorization["targetReleaseId"])
    expected_target = store_root / "releases" / target_id
    if target_root.resolve() != expected_target.resolve():
        raise ManifestError("Windows forward-fix target is outside authorized store")
    target = validate_release(
        target_root, version_file, source_revision, channel, qt_version,
        signer_sha256, update_public_key_sha256, now_utc)
    if (target["releaseId"] != target_id
            or target["manifestSequence"]
                != authorization["targetManifestSequence"]):
        raise ManifestError("Windows forward-fix staged target differs")
    before = inspect_active(store_root, now_utc)
    if (before["channel"] != channel
            or before["releaseId"] != authorization["restoredReleaseId"]
            or before["manifestSequence"]
                != authorization["restoredManifestSequence"]):
        raise ManifestError("Windows forward-fix active pointer is not restored release")
    restored_root = store_root / "releases" / str(before["releaseId"])
    restored = validate_release_from_candidate(restored_root, now_utc)
    if restored["releaseId"] != incident["restoredReleaseId"]:
        raise ManifestError("Windows forward-fix restored release differs from incident")

    authorization_digest = _digest(authorization_path)
    consumption = (
        store_root / ".forward-fix-consumptions"
        / f"{authorization_digest}.json")
    _write_once(consumption, {
        "schemaVersion": 1,
        "status": "consumed-before-mutation",
        "authorizationSha256": authorization_digest,
        "incidentId": incident["incidentId"],
        "restoredReleaseId": restored["releaseId"],
        "releaseId": target_id,
        "consumedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }, "Windows forward-fix authorization consumption marker")

    executed_at = now_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
    evidence = {
        "schemaVersion": 1,
        "evidenceType": "windows-update-forward-fix-pointer-execution",
        "status": STATUS,
        "adapter": "atomic-filesystem-update-pointer",
        "channel": channel,
        "incidentId": incident["incidentId"],
        "failedReleaseId": incident["failedReleaseId"],
        "restoredReleaseId": restored["releaseId"],
        "releaseId": target_id,
        "failedManifestSequence": authorization["failedManifestSequence"],
        "restoredManifestSequence": restored["manifestSequence"],
        "manifestSequence": target["manifestSequence"],
        "version": target["version"],
        "sourceRevision": target["sourceRevision"],
        "authorizationSha256": authorization_digest,
        "executedAt": executed_at,
    }
    pointer_path = store_root / "active-channel.json"
    previous = {key: before[key] for key in POINTER_KEYS}
    try:
        atomic_write(pointer_path, json.dumps(
            _pointer(target, executed_at), ensure_ascii=True,
            indent=2, sort_keys=True) + "\n")
        after = inspect_active(store_root, now_utc)
        if after["releaseId"] != target_id:
            raise ManifestError("Windows forward-fix pointer did not activate target")
        _write_once(evidence_path, evidence, "Windows forward-fix execution evidence")
    except Exception:
        atomic_write(pointer_path, json.dumps(
            previous, ensure_ascii=True, indent=2, sort_keys=True) + "\n")
        raise
    return evidence


def verify_execution(
    evidence_path: Path,
    authorization_path: Path,
    authorization_inputs: tuple[object, ...],
) -> dict[str, object]:
    values = _unpack(authorization_inputs)
    value = _read_json(evidence_path, "Windows forward-fix execution evidence")
    if set(value) != EVIDENCE_KEYS:
        raise ManifestError("Windows forward-fix execution evidence shape is invalid")
    try:
        executed = datetime.strptime(
            str(value["executedAt"]), "%Y-%m-%dT%H:%M:%SZ").replace(
                tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError("Windows forward-fix execution time is invalid") from error
    authorization = verify_authorization(
        authorization_path, *values, executed)
    rollback_inputs = values[1]
    target_root, version_file, source_revision = values[2:5]
    qt_version, signer_sha256, update_public_key_sha256 = values[5:8]
    target = validate_release(
        target_root, version_file, source_revision, str(authorization["channel"]),
        qt_version, signer_sha256, update_public_key_sha256, executed)
    expected = {
        "schemaVersion": 1,
        "evidenceType": "windows-update-forward-fix-pointer-execution",
        "status": STATUS,
        "adapter": "atomic-filesystem-update-pointer",
        "channel": authorization["channel"],
        "incidentId": _digest(rollback_inputs[1]),
        "failedReleaseId": authorization["failedReleaseId"],
        "restoredReleaseId": authorization["restoredReleaseId"],
        "releaseId": target["releaseId"],
        "failedManifestSequence": authorization["failedManifestSequence"],
        "restoredManifestSequence": authorization["restoredManifestSequence"],
        "manifestSequence": target["manifestSequence"],
        "version": target["version"],
        "sourceRevision": target["sourceRevision"],
        "authorizationSha256": _digest(authorization_path),
        "executedAt": value["executedAt"],
    }
    if value != expected or authorization["targetReleaseId"] != target["releaseId"]:
        raise ManifestError("Windows forward-fix execution evidence differs from inputs")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("execute", "verify"))
    parser.add_argument("--authorization", type=Path, required=True)
    add_authorization_arguments(parser)
    parser.add_argument("--store-root", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    values = authorization_values(args)
    try:
        if args.command == "execute":
            if args.store_root is None:
                raise ManifestError("Windows forward-fix store root is required")
            result = execute(
                args.authorization, values, args.store_root, args.output,
                datetime.now(timezone.utc).replace(microsecond=0))
        else:
            result = verify_execution(args.output, args.authorization, values)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows forward-fix execution failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
