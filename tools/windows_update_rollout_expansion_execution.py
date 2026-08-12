#!/usr/bin/env python3
"""Consume one rollout-expansion authorization through an atomic channel pointer."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError, atomic_write
from windows_update_channel_store import validate_release, validate_release_from_candidate
from windows_update_incident_state import require_no_open_incident
from windows_update_manifest import verify_manifest_signature
from windows_update_release_execution import (
    POINTER_KEYS, _digest, _pointer, _read_json, _write_once, inspect_active,
)
from windows_update_rollout_expansion_authorization import (
    add_authorization_arguments,
    authorization_values,
    verify_authorization,
)


STATUS = "rollout-expansion-pointer-switched-awaiting-external-observation"
EVIDENCE_KEYS = {
    "schemaVersion", "evidenceType", "status", "adapter", "channel",
    "releaseId", "rollbackReleaseId", "manifestSequence",
    "rollbackManifestSequence", "version", "sourceRevision",
    "currentRolloutPercentage", "targetRolloutPercentage", "rolloutSeed",
    "authorizationSha256", "healthDecisionSha256", "metricsSha256",
    "executedAt",
}


def _unpack(values: tuple[object, ...]):
    if len(values) != 21:
        raise ManifestError("Windows rollout expansion execution inputs are incomplete")
    return values


def _manifest(root: Path, now_utc: datetime) -> dict[str, object]:
    return verify_manifest_signature(
        root / "update/manifest.json", root / "update/manifest.json.sig",
        root / "evidence/update-public-key.pem", now_utc)


def execute(
    authorization_path: Path,
    authorization_inputs: tuple[object, ...],
    store_root: Path,
    evidence_path: Path,
    now_utc: datetime,
) -> dict[str, object]:
    values = _unpack(authorization_inputs)
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows rollout expansion execution clock must be exact UTC")
    authorization = verify_authorization(
        authorization_path, *values, now_utc=now_utc)
    (completion_path, execution_path, promotion_authorization_path,
     current_root, rollback_release_root, pre_promotion_manifest_path,
     promotion_observation_path, health_path, metrics_path,
     metrics_signature_path, metrics_public_key_path, metrics_key_id,
     metrics_public_key_sha256, health_policy_path, target_root,
     version_file, source_revision, channel, qt_version, signer_sha256,
     update_public_key_sha256) = values
    if (not isinstance(current_root, Path) or not isinstance(target_root, Path)
            or not isinstance(version_file, Path)
            or not isinstance(store_root, Path) or not store_root.is_absolute()
            or store_root.is_symlink() or not store_root.is_dir()
            or (store_root / "releases").is_symlink()
            or (store_root / "active-channel.json").is_symlink()):
        raise ManifestError("Windows rollout expansion execution store boundary is unsafe")
    require_no_open_incident(store_root, channel, now_utc)
    target_id = str(authorization["targetManifestSha256"])
    current_id = str(authorization["expectedCurrentManifestSha256"])
    expected_target = store_root / "releases" / target_id
    expected_current = store_root / "releases" / current_id
    if (target_root.resolve() != expected_target.resolve()
            or current_root.resolve() != expected_current.resolve()):
        raise ManifestError("Windows rollout expansion candidates are outside authorized store")
    target = validate_release(
        target_root, version_file, source_revision, channel, qt_version,
        signer_sha256, update_public_key_sha256, now_utc)
    if (target["releaseId"] != target_id
            or target["manifestSequence"] != authorization["targetManifestSequence"]):
        raise ManifestError("Windows rollout expansion staged target differs")
    before = inspect_active(store_root, now_utc)
    if (before["channel"] != channel or before["releaseId"] != current_id
            or before["manifestSequence"]
                != authorization["currentManifestSequence"]):
        raise ManifestError("Windows rollout expansion active pointer changed")
    current_manifest = _manifest(current_root, now_utc)
    target_manifest = _manifest(target_root, now_utc)
    if (current_manifest["rollout"]["percentage"]
            != authorization["currentRolloutPercentage"]
            or target_manifest["rollout"]["percentage"]
                != authorization["targetRolloutPercentage"]
            or current_manifest["rollout"]["seed"] != authorization["rolloutSeed"]
            or target_manifest["rollout"]["seed"] != authorization["rolloutSeed"]):
        raise ManifestError("Windows rollout expansion staged rollout identity differs")

    authorization_digest = _digest(authorization_path)
    consumption = (
        store_root / ".rollout-expansion-consumptions"
        / f"{authorization_digest}.json")
    _write_once(consumption, {
        "schemaVersion": 1,
        "status": "consumed-before-mutation",
        "authorizationSha256": authorization_digest,
        "releaseId": target_id,
        "rollbackReleaseId": current_id,
        "consumedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }, "Windows rollout expansion authorization consumption marker")

    executed_at = now_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
    evidence = {
        "schemaVersion": 1,
        "evidenceType": "windows-update-rollout-expansion-pointer-execution",
        "status": STATUS,
        "adapter": "atomic-filesystem-update-pointer",
        "channel": channel,
        "releaseId": target_id,
        "rollbackReleaseId": current_id,
        "manifestSequence": target["manifestSequence"],
        "rollbackManifestSequence": before["manifestSequence"],
        "version": target["version"],
        "sourceRevision": target["sourceRevision"],
        "currentRolloutPercentage": authorization["currentRolloutPercentage"],
        "targetRolloutPercentage": authorization["targetRolloutPercentage"],
        "rolloutSeed": authorization["rolloutSeed"],
        "authorizationSha256": authorization_digest,
        "healthDecisionSha256": authorization["healthDecisionSha256"],
        "metricsSha256": authorization["metricsSha256"],
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
            raise ManifestError("Windows rollout expansion pointer did not activate target")
        _write_once(evidence_path, evidence, "Windows rollout expansion execution evidence")
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
    value = _read_json(evidence_path, "Windows rollout expansion execution evidence")
    if set(value) != EVIDENCE_KEYS:
        raise ManifestError("Windows rollout expansion execution evidence shape is invalid")
    try:
        executed = datetime.strptime(
            str(value["executedAt"]), "%Y-%m-%dT%H:%M:%SZ").replace(
                tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError("Windows rollout expansion execution time is invalid") from error
    authorization = verify_authorization(
        authorization_path, *values, now_utc=executed)
    current_root = values[3]
    target_root = values[14]
    version_file, source_revision, channel, qt_version = values[15:19]
    signer_sha256, update_public_key_sha256 = values[19:21]
    target = validate_release(
        target_root, version_file, source_revision, channel, qt_version,
        signer_sha256, update_public_key_sha256, executed)
    rollback = validate_release_from_candidate(current_root, executed)
    expected = {
        "schemaVersion": 1,
        "evidenceType": "windows-update-rollout-expansion-pointer-execution",
        "status": STATUS,
        "adapter": "atomic-filesystem-update-pointer",
        "channel": channel,
        "releaseId": target["releaseId"],
        "rollbackReleaseId": rollback["releaseId"],
        "manifestSequence": target["manifestSequence"],
        "rollbackManifestSequence": rollback["manifestSequence"],
        "version": target["version"],
        "sourceRevision": target["sourceRevision"],
        "currentRolloutPercentage": authorization["currentRolloutPercentage"],
        "targetRolloutPercentage": authorization["targetRolloutPercentage"],
        "rolloutSeed": authorization["rolloutSeed"],
        "authorizationSha256": _digest(authorization_path),
        "healthDecisionSha256": authorization["healthDecisionSha256"],
        "metricsSha256": authorization["metricsSha256"],
        "executedAt": value["executedAt"],
    }
    if (value != expected
            or authorization["targetManifestSha256"] != target["releaseId"]
            or authorization["expectedCurrentManifestSha256"] != rollback["releaseId"]):
        raise ManifestError("Windows rollout expansion execution evidence differs")
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
                raise ManifestError("Windows rollout expansion store root is required")
            result = execute(
                args.authorization, values, args.store_root, args.output,
                datetime.now(timezone.utc).replace(microsecond=0))
        else:
            result = verify_execution(args.output, args.authorization, values)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows rollout expansion execution failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
