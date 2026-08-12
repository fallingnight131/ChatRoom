#!/usr/bin/env python3
"""Consume one authorization for an atomic local Windows update-channel switch."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError, atomic_write, sha256_file
from windows_update_channel_store import validate_release, validate_release_from_candidate
from windows_update_incident_state import require_no_open_incident
from windows_update_release_authorization import verify_authorization


STATUS = "channel-pointer-switched-awaiting-external-observation"
POINTER_KEYS = {
    "schemaVersion", "channel", "releaseId", "manifestSequence", "version",
    "sourceRevision", "activatedAt",
}
EVIDENCE_KEYS = {
    "schemaVersion", "evidenceType", "status", "adapter", "channel",
    "releaseId", "rollbackReleaseId", "manifestSequence",
    "rollbackManifestSequence", "version", "sourceRevision",
    "authorizationSha256", "executedAt",
}


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Windows update execution input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _read_json(path: Path, label: str) -> dict[str, object]:
    if path.is_symlink() or not path.is_file():
        raise ManifestError(f"{label} must be a regular file")

    def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError(f"{label} has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError(f"{label} is unreadable") from error
    if not isinstance(value, dict):
        raise ManifestError(f"{label} must be an object")
    return value


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


def _pointer(identity: dict[str, object], activated_at: str) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "channel": identity["channel"],
        "releaseId": identity["releaseId"],
        "manifestSequence": identity["manifestSequence"],
        "version": identity["version"],
        "sourceRevision": identity["sourceRevision"],
        "activatedAt": activated_at,
    }


def inspect_active(store_root: Path, now_utc: datetime) -> dict[str, object]:
    pointer = _read_json(store_root / "active-channel.json", "Active Windows update pointer")
    if set(pointer) != POINTER_KEYS or pointer.get("schemaVersion") != 1:
        raise ManifestError("Active Windows update pointer has an unsupported shape")
    release_id = pointer.get("releaseId")
    if (not isinstance(release_id, str) or len(release_id) != 64
            or any(character not in "0123456789abcdef" for character in release_id)):
        raise ManifestError("Active Windows update pointer release ID is invalid")
    try:
        activated = datetime.strptime(
            str(pointer.get("activatedAt")), "%Y-%m-%dT%H:%M:%SZ").replace(
                tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError("Active Windows update pointer time is invalid") from error
    if activated > now_utc:
        raise ManifestError("Active Windows update pointer time is from the future")
    release = store_root / "releases" / release_id
    identity = validate_release_from_candidate(release, now_utc)
    expected = _pointer(identity, str(pointer.get("activatedAt")))
    if pointer != expected:
        raise ManifestError("Active Windows update pointer does not match immutable release")
    return {"status": "healthy", **pointer}


def execute(
    authorization_path: Path,
    candidate_root: Path,
    current_manifest_path: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    authenticode_signer_sha256: str,
    public_key_file_sha256: str,
    store_root: Path,
    evidence_path: Path,
    now_utc: datetime,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows update execution clock must be an exact UTC second")
    authorization = verify_authorization(
        authorization_path, candidate_root, current_manifest_path, version_file,
        source_revision, channel, qt_version, authenticode_signer_sha256,
        public_key_file_sha256, now_utc,
    )
    if (not store_root.is_absolute() or store_root.is_symlink() or not store_root.is_dir()
            or (store_root / "releases").is_symlink()
            or (store_root / "active-channel.json").is_symlink()):
        raise ManifestError("Windows update execution store boundary is unsafe")
    require_no_open_incident(store_root, channel, now_utc)
    release_id = str(authorization["updateManifestSha256"])
    target_root = store_root / "releases" / release_id
    if candidate_root.resolve() != target_root.resolve():
        raise ManifestError("Windows update candidate is outside the authorized store")
    target = validate_release(
        target_root, version_file, source_revision, channel, qt_version,
        authenticode_signer_sha256, public_key_file_sha256, now_utc,
    )
    if (target["releaseId"] != release_id
            or target["manifestSequence"] != authorization["manifestSequence"]):
        raise ManifestError("Windows update staged target does not match authorization")
    before = inspect_active(store_root, now_utc)
    if (before["channel"] != channel
            or before["releaseId"] != authorization["expectedCurrentManifestSha256"]
            or before["manifestSequence"] != authorization["expectedCurrentManifestSequence"]):
        raise ManifestError("Windows update active pointer is not the authorized current release")
    current_release_manifest = (
        store_root / "releases" / str(before["releaseId"]) / "update/manifest.json")
    if (_digest(current_release_manifest) != _digest(current_manifest_path)
            or current_release_manifest.read_bytes() != current_manifest_path.read_bytes()):
        raise ManifestError("Windows update current snapshot does not match active release")

    authorization_digest = _digest(authorization_path)
    consumption = store_root / ".promotion-consumptions" / f"{authorization_digest}.json"
    _write_once(consumption, {
        "schemaVersion": 1,
        "status": "consumed-before-mutation",
        "authorizationSha256": authorization_digest,
        "releaseId": release_id,
        "rollbackReleaseId": before["releaseId"],
        "consumedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }, "Windows update authorization consumption marker")

    executed_at = now_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
    evidence = {
        "schemaVersion": 1,
        "evidenceType": "windows-update-channel-pointer-execution",
        "status": STATUS,
        "adapter": "atomic-filesystem-update-pointer",
        "channel": channel,
        "releaseId": release_id,
        "rollbackReleaseId": before["releaseId"],
        "manifestSequence": target["manifestSequence"],
        "rollbackManifestSequence": before["manifestSequence"],
        "version": target["version"],
        "sourceRevision": target["sourceRevision"],
        "authorizationSha256": authorization_digest,
        "executedAt": executed_at,
    }
    pointer_path = store_root / "active-channel.json"
    previous_pointer = {key: before[key] for key in POINTER_KEYS}
    try:
        atomic_write(pointer_path, json.dumps(
            _pointer(target, executed_at), ensure_ascii=True, indent=2, sort_keys=True) + "\n")
        after = inspect_active(store_root, now_utc)
        if after["releaseId"] != release_id:
            raise ManifestError("Windows update pointer did not activate the candidate")
        _write_once(evidence_path, evidence,
                    "Windows update execution evidence")
    except Exception:
        atomic_write(pointer_path, json.dumps(
            previous_pointer, ensure_ascii=True, indent=2, sort_keys=True) + "\n")
        raise
    return evidence


def verify_execution(
    evidence_path: Path,
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
) -> dict[str, object]:
    value = _read_json(evidence_path, "Windows update execution evidence")
    if set(value) != EVIDENCE_KEYS:
        raise ManifestError("Windows update execution evidence has an unsupported shape")
    try:
        executed = datetime.strptime(
            str(value["executedAt"]), "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError("Windows update execution time is invalid") from error
    authorization = verify_authorization(
        authorization_path, candidate_root, current_manifest_path, version_file,
        source_revision, channel, qt_version, authenticode_signer_sha256,
        public_key_file_sha256, executed,
    )
    target = validate_release(
        candidate_root, version_file, source_revision, channel, qt_version,
        authenticode_signer_sha256, public_key_file_sha256, executed,
    )
    rollback = validate_release_from_candidate(rollback_release_root, executed)
    expected = {
        "schemaVersion": 1,
        "evidenceType": "windows-update-channel-pointer-execution",
        "status": STATUS,
        "adapter": "atomic-filesystem-update-pointer",
        "channel": channel,
        "releaseId": target["releaseId"],
        "rollbackReleaseId": rollback["releaseId"],
        "manifestSequence": target["manifestSequence"],
        "rollbackManifestSequence": rollback["manifestSequence"],
        "version": target["version"],
        "sourceRevision": target["sourceRevision"],
        "authorizationSha256": _digest(authorization_path),
        "executedAt": value["executedAt"],
    }
    if (value != expected
            or authorization["updateManifestSha256"] != target["releaseId"]
            or authorization["expectedCurrentManifestSha256"] != rollback["releaseId"]):
        raise ManifestError("Windows update execution evidence does not match authorization")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("execute", "verify"))
    parser.add_argument("--authorization", type=Path, required=True)
    parser.add_argument("--candidate-root", type=Path, required=True)
    parser.add_argument("--rollback-release-root", type=Path)
    parser.add_argument("--current-manifest", type=Path, required=True)
    parser.add_argument("--version-file", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    parser.add_argument("--qt-version", required=True)
    parser.add_argument("--authenticode-signer-sha256", required=True)
    parser.add_argument("--public-key-file-sha256", required=True)
    parser.add_argument("--store-root", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    now = datetime.now(timezone.utc).replace(microsecond=0)
    try:
        if args.command == "execute":
            if args.store_root is None:
                raise ManifestError("Windows update execution store root is required")
            value = execute(
                args.authorization, args.candidate_root, args.current_manifest,
                args.version_file, args.source_revision, args.channel,
                args.qt_version, args.authenticode_signer_sha256,
                args.public_key_file_sha256, args.store_root, args.output, now,
            )
        else:
            if args.rollback_release_root is None:
                raise ManifestError("Windows update rollback release root is required")
            value = verify_execution(
                args.output, args.authorization, args.candidate_root,
                args.rollback_release_root, args.current_manifest,
                args.version_file, args.source_revision, args.channel,
                args.qt_version, args.authenticode_signer_sha256,
                args.public_key_file_sha256,
            )
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows update execution failed: {error}") from None
    print(json.dumps(value, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
