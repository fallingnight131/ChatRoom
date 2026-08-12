#!/usr/bin/env python3
"""Consume Web promotion authorization for one atomic local release-pointer switch."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError
from web_release_authorization import verify_authorization
from web_release_store import activate_release, inspect_active_release, validate_release


STATUS = "pointer-switched-awaiting-external-observation"
KEYS = {
    "schemaVersion", "evidenceType", "status", "adapter", "baseUrl",
    "releaseId", "rollbackReleaseId", "version", "sourceRevision",
    "authorizationSha256", "executedAt",
}


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Web release execution input must be a regular file")
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


def _release_path(store_root: Path, release_id: str) -> Path:
    return store_root / "releases" / release_id


def execute(
    authorization_path: Path,
    technical_promotion_path: Path,
    release_root: Path,
    release_observation: Path,
    route_observation: Path,
    rollback_release_root: Path,
    rollback_observation: Path,
    store_root: Path,
    evidence_path: Path,
    now_utc: datetime,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Web release execution clock must be an exact UTC second")
    authorization = verify_authorization(
        authorization_path, technical_promotion_path, release_root,
        release_observation, route_observation, rollback_release_root,
        rollback_observation, now_utc,
    )
    release_id = str(authorization["releaseId"])
    rollback_id = str(authorization["rollbackReleaseId"])
    if (store_root.is_symlink() or not store_root.is_dir()
            or (store_root / "releases").is_symlink()
            or (store_root / "active-release.json").is_symlink()):
        raise ManifestError("Web release execution store boundary is unsafe")
    expected_release = _release_path(store_root, release_id).resolve()
    expected_rollback = _release_path(store_root, rollback_id).resolve()
    if release_root.resolve() != expected_release or rollback_release_root.resolve() != expected_rollback:
        raise ManifestError("Web release execution inputs are outside the authorized store")
    if validate_release(expected_release)["releaseId"] != release_id:
        raise ManifestError("Web release execution candidate is invalid")
    if validate_release(expected_rollback)["releaseId"] != rollback_id:
        raise ManifestError("Web release execution rollback target is invalid")
    before = inspect_active_release(store_root)
    if before["releaseId"] != rollback_id:
        raise ManifestError("Web release execution active pointer is not the authorized rollback target")

    authorization_digest = _digest(authorization_path)
    consumption = store_root / ".promotion-consumptions" / f"{authorization_digest}.json"
    _write_once(consumption, {
        "schemaVersion": 1,
        "status": "consumed-before-mutation",
        "authorizationSha256": authorization_digest,
        "releaseId": release_id,
        "rollbackReleaseId": rollback_id,
        "consumedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }, "Web release authorization consumption marker")

    evidence = {
        "schemaVersion": 1,
        "evidenceType": "web-release-pointer-execution",
        "status": STATUS,
        "adapter": "atomic-filesystem-release-pointer",
        "baseUrl": authorization["baseUrl"],
        "releaseId": release_id,
        "rollbackReleaseId": rollback_id,
        "version": authorization["version"],
        "sourceRevision": authorization["sourceRevision"],
        "authorizationSha256": authorization_digest,
        "executedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    try:
        activate_release(store_root, release_id, evidence["executedAt"])
        after = inspect_active_release(store_root)
        if after["releaseId"] != release_id:
            raise ManifestError("Web release execution pointer did not activate the candidate")
        _write_once(evidence_path.resolve(strict=False), evidence,
                    "Web release execution evidence")
    except Exception:
        activate_release(store_root, rollback_id, now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"))
        raise
    return evidence


def verify_execution(
    evidence_path: Path,
    authorization_path: Path,
    technical_promotion_path: Path,
    release_root: Path,
    release_observation: Path,
    route_observation: Path,
    rollback_release_root: Path,
    rollback_observation: Path,
) -> dict[str, object]:
    if evidence_path.is_symlink() or not evidence_path.is_file():
        raise ManifestError("Web release execution evidence must be a regular file")

    def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, item in pairs:
            if key in result:
                raise ManifestError("Web release execution evidence has duplicate keys")
            result[key] = item
        return result

    try:
        value = json.loads(
            evidence_path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Web release execution evidence is unreadable") from error
    if not isinstance(value, dict) or set(value) != KEYS:
        raise ManifestError("Web release execution evidence has an unsupported shape")
    try:
        executed = datetime.strptime(str(value["executedAt"]), "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError("Web release execution time is invalid") from error
    authorization = verify_authorization(
        authorization_path, technical_promotion_path, release_root,
        release_observation, route_observation, rollback_release_root,
        rollback_observation, executed,
    )
    expected = {
        "schemaVersion": 1,
        "evidenceType": "web-release-pointer-execution",
        "status": STATUS,
        "adapter": "atomic-filesystem-release-pointer",
        "baseUrl": authorization["baseUrl"],
        "releaseId": authorization["releaseId"],
        "rollbackReleaseId": authorization["rollbackReleaseId"],
        "version": authorization["version"],
        "sourceRevision": authorization["sourceRevision"],
        "authorizationSha256": _digest(authorization_path),
        "executedAt": value["executedAt"],
    }
    if value != expected:
        raise ManifestError("Web release execution evidence does not match authorization")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("execute", "verify"))
    parser.add_argument("--authorization", type=Path, required=True)
    parser.add_argument("--technical-promotion", type=Path, required=True)
    parser.add_argument("--release-root", type=Path, required=True)
    parser.add_argument("--release-observation", type=Path, required=True)
    parser.add_argument("--route-observation", type=Path, required=True)
    parser.add_argument("--rollback-release-root", type=Path, required=True)
    parser.add_argument("--rollback-observation", type=Path, required=True)
    parser.add_argument("--store-root", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    now = datetime.now(timezone.utc).replace(microsecond=0)
    try:
        if args.command == "execute":
            if args.store_root is None:
                raise ManifestError("Web release execution store root is required")
            value = execute(
                args.authorization, args.technical_promotion, args.release_root,
                args.release_observation, args.route_observation,
                args.rollback_release_root, args.rollback_observation,
                args.store_root, args.output, now,
            )
        else:
            value = verify_execution(
                args.output, args.authorization, args.technical_promotion,
                args.release_root, args.release_observation,
                args.route_observation, args.rollback_release_root,
                args.rollback_observation,
            )
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web release execution failed: {error}") from None
    print(json.dumps(value, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
