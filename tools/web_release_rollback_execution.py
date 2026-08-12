#!/usr/bin/env python3
"""Restore the exact pre-authorized Web rollback pointer from execution evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError
from web_release_execution import verify_execution
from web_release_store import activate_release, inspect_active_release, validate_release


KEYS = {
    "schemaVersion", "evidenceType", "status", "adapter", "baseUrl",
    "failedReleaseId", "restoredReleaseId", "executionSha256", "executedAt",
    "rollbackExecutedAt",
}


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Web rollback execution input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_once(path: Path, value: dict[str, object], label: str) -> None:
    if path.exists() or path.is_symlink() or not path.is_absolute():
        raise ManifestError(f"{label} is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError(f"{label} directory is unsafe")
    temporary: Path | None = None
    rendered = json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n"
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


def rollback(
    execution_path: Path,
    authorization_path: Path,
    technical_promotion_path: Path,
    release_root: Path,
    pre_release_observation: Path,
    pre_route_observation: Path,
    rollback_release_root: Path,
    rollback_observation: Path,
    store_root: Path,
    evidence_path: Path,
    now_utc: datetime,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Web rollback execution clock must be an exact UTC second")
    execution = verify_execution(
        execution_path, authorization_path, technical_promotion_path,
        release_root, pre_release_observation, pre_route_observation,
        rollback_release_root, rollback_observation,
    )
    failed_id = str(execution["releaseId"])
    restored_id = str(execution["rollbackReleaseId"])
    if (store_root.is_symlink() or not store_root.is_dir()
            or (store_root / "releases").is_symlink()
            or (store_root / "active-release.json").is_symlink()):
        raise ManifestError("Web rollback execution store boundary is unsafe")
    if (release_root.resolve() != (store_root / "releases" / failed_id).resolve()
            or rollback_release_root.resolve()
            != (store_root / "releases" / restored_id).resolve()
            or validate_release(release_root)["releaseId"] != failed_id
            or validate_release(rollback_release_root)["releaseId"] != restored_id):
        raise ManifestError("Web rollback execution release paths are invalid")
    if inspect_active_release(store_root)["releaseId"] != failed_id:
        raise ManifestError("Web rollback execution active pointer is not the failed release")

    execution_digest = _digest(execution_path)
    marker = store_root / ".rollback-consumptions" / f"{execution_digest}.json"
    rollback_time = now_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
    _write_once(marker, {
        "schemaVersion": 1,
        "status": "rollback-consumed-before-mutation",
        "executionSha256": execution_digest,
        "failedReleaseId": failed_id,
        "restoredReleaseId": restored_id,
        "consumedAt": rollback_time,
    }, "Web rollback execution consumption marker")

    activate_release(store_root, restored_id, rollback_time)
    if inspect_active_release(store_root)["releaseId"] != restored_id:
        raise ManifestError("Web rollback execution pointer did not restore the prior release")
    evidence = {
        "schemaVersion": 1,
        "evidenceType": "web-release-rollback-pointer-execution",
        "status": "rollback-pointer-restored-awaiting-external-observation",
        "adapter": "atomic-filesystem-release-pointer",
        "baseUrl": execution["baseUrl"],
        "failedReleaseId": failed_id,
        "restoredReleaseId": restored_id,
        "executionSha256": execution_digest,
        "executedAt": execution["executedAt"],
        "rollbackExecutedAt": rollback_time,
    }
    _write_once(evidence_path.resolve(strict=False), evidence,
                "Web rollback execution evidence")
    return evidence


def verify_rollback(
    evidence_path: Path,
    execution_path: Path,
    authorization_path: Path,
    technical_promotion_path: Path,
    release_root: Path,
    pre_release_observation: Path,
    pre_route_observation: Path,
    rollback_release_root: Path,
    rollback_observation: Path,
) -> dict[str, object]:
    if evidence_path.is_symlink() or not evidence_path.is_file():
        raise ManifestError("Web rollback execution evidence must be a regular file")

    def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Web rollback execution evidence has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(
            evidence_path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Web rollback execution evidence is unreadable") from error
    if not isinstance(value, dict) or set(value) != KEYS:
        raise ManifestError("Web rollback execution evidence has an unsupported shape")
    execution = verify_execution(
        execution_path, authorization_path, technical_promotion_path,
        release_root, pre_release_observation, pre_route_observation,
        rollback_release_root, rollback_observation,
    )
    try:
        executed = datetime.strptime(str(execution["executedAt"]), "%Y-%m-%dT%H:%M:%SZ")
        rolled_back = datetime.strptime(str(value["rollbackExecutedAt"]), "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise ManifestError("Web rollback execution time is invalid") from error
    if rolled_back < executed:
        raise ManifestError("Web rollback execution precedes promotion execution")
    expected = {
        "schemaVersion": 1,
        "evidenceType": "web-release-rollback-pointer-execution",
        "status": "rollback-pointer-restored-awaiting-external-observation",
        "adapter": "atomic-filesystem-release-pointer",
        "baseUrl": execution["baseUrl"],
        "failedReleaseId": execution["releaseId"],
        "restoredReleaseId": execution["rollbackReleaseId"],
        "executionSha256": _digest(execution_path),
        "executedAt": execution["executedAt"],
        "rollbackExecutedAt": value["rollbackExecutedAt"],
    }
    if value != expected:
        raise ManifestError("Web rollback execution evidence does not match promotion execution")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("execute", "verify"))
    for name in ("execution", "authorization", "technical-promotion",
                 "release-root", "pre-release-observation", "pre-route-observation",
                 "rollback-release-root", "rollback-observation", "output"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--store-root", type=Path)
    args = parser.parse_args()
    now = datetime.now(timezone.utc).replace(microsecond=0)
    common = (
        args.execution, args.authorization, args.technical_promotion,
        args.release_root, args.pre_release_observation,
        args.pre_route_observation, args.rollback_release_root,
        args.rollback_observation,
    )
    try:
        if args.command == "execute":
            if args.store_root is None:
                raise ManifestError("Web rollback execution store root is required")
            result = rollback(*common, args.store_root, args.output, now)
        else:
            result = verify_rollback(args.output, *common)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web rollback execution failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
