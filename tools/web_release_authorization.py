#!/usr/bin/env python3
"""Create or verify a short-lived Web production-promotion authorization."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError
from web_promotion_evidence import verify_promotion_evidence


ENVIRONMENT = "web-production"
STATUS = "production-promotion-approved-not-executed"
KEYS = {
    "schemaVersion", "authorizationType", "status", "environment", "baseUrl",
    "candidateBaseUrl",
    "releaseId", "rollbackReleaseId", "version", "sourceRevision",
    "technicalPromotionSha256", "approvedAt", "expiresAt",
}


def _time(value: object, label: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ManifestError(f"Web release {label} is invalid")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise ManifestError(f"Web release {label} is invalid") from error
    if parsed.tzinfo != timezone.utc or parsed.microsecond:
        raise ManifestError(f"Web release {label} is invalid")
    return parsed


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Web release authorization input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _promotion(
    promotion_path: Path,
    release_root: Path,
    release_observation: Path,
    route_observation: Path,
    rollback_release_root: Path,
    rollback_observation: Path,
) -> dict[str, object]:
    return verify_promotion_evidence(
        promotion_path, release_root, release_observation, route_observation,
        rollback_release_root, rollback_observation,
    )


def create_authorization(
    promotion_path: Path,
    release_root: Path,
    release_observation: Path,
    route_observation: Path,
    rollback_release_root: Path,
    rollback_observation: Path,
    now_utc: datetime,
    lifetime_seconds: int = 900,
) -> dict[str, object]:
    if not 60 <= lifetime_seconds <= 900:
        raise ManifestError("Web release authorization lifetime must be 60 to 900 seconds")
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Web release authorization clock must be an exact UTC second")
    promotion = _promotion(
        promotion_path, release_root, release_observation, route_observation,
        rollback_release_root, rollback_observation,
    )
    try:
        technical_approved = datetime.fromisoformat(str(promotion["approvedAt"]))
    except ValueError as error:
        raise ManifestError("Web technical promotion approval time is invalid") from error
    if (technical_approved.tzinfo is None
            or technical_approved.astimezone(timezone.utc) > now_utc
            or now_utc - technical_approved.astimezone(timezone.utc) > timedelta(minutes=15)):
        raise ManifestError("Web technical promotion approval is stale or from the future")
    return {
        "schemaVersion": 2,
        "authorizationType": "web-production-promotion",
        "status": STATUS,
        "environment": ENVIRONMENT,
        "baseUrl": promotion["productionBaseUrl"],
        "candidateBaseUrl": promotion["candidateBaseUrl"],
        "releaseId": promotion["releaseId"],
        "rollbackReleaseId": promotion["rollbackReleaseId"],
        "version": promotion["version"],
        "sourceRevision": promotion["sourceRevision"],
        "technicalPromotionSha256": _digest(promotion_path),
        "approvedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "expiresAt": (now_utc + timedelta(seconds=lifetime_seconds)).strftime(
            "%Y-%m-%dT%H:%M:%SZ"),
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if path.exists() or path.is_symlink() or not path.is_absolute():
        raise ManifestError("Web release authorization output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
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
            raise ManifestError("Web release authorization output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_authorization(
    authorization_path: Path,
    promotion_path: Path,
    release_root: Path,
    release_observation: Path,
    route_observation: Path,
    rollback_release_root: Path,
    rollback_observation: Path,
    now_utc: datetime,
) -> dict[str, object]:
    if authorization_path.is_symlink() or not authorization_path.is_file():
        raise ManifestError("Web release authorization must be a regular file")

    def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Web release authorization has duplicate keys")
            result[key] = value
        return result

    try:
        recorded = json.loads(
            authorization_path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Web release authorization is unreadable") from error
    if not isinstance(recorded, dict) or set(recorded) != KEYS:
        raise ManifestError("Web release authorization has an unsupported shape")
    approved = _time(recorded.get("approvedAt"), "approval time")
    expires = _time(recorded.get("expiresAt"), "expiry time")
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Web release authorization clock must be an exact UTC second")
    lifetime = expires - approved
    if lifetime < timedelta(seconds=60) or lifetime > timedelta(seconds=900):
        raise ManifestError("Web release authorization lifetime is invalid")
    if approved > now_utc + timedelta(minutes=1) or now_utc >= expires:
        raise ManifestError("Web release authorization is expired or from the future")
    expected = create_authorization(
        promotion_path, release_root, release_observation, route_observation,
        rollback_release_root, rollback_observation, approved, int(lifetime.total_seconds()),
    )
    if recorded != expected:
        raise ManifestError("Web release authorization does not match its technical inputs")
    return recorded


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("create", "verify"))
    parser.add_argument("--technical-promotion", type=Path, required=True)
    parser.add_argument("--release-root", type=Path, required=True)
    parser.add_argument("--release-observation", type=Path, required=True)
    parser.add_argument("--route-observation", type=Path, required=True)
    parser.add_argument("--rollback-release-root", type=Path, required=True)
    parser.add_argument("--rollback-observation", type=Path, required=True)
    parser.add_argument("--lifetime-seconds", type=int, default=900)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    now = datetime.now(timezone.utc).replace(microsecond=0)
    try:
        if args.command == "create":
            value = create_authorization(
                args.technical_promotion, args.release_root, args.release_observation,
                args.route_observation, args.rollback_release_root,
                args.rollback_observation, now, args.lifetime_seconds,
            )
            write_once(args.output.resolve(strict=False), value)
        else:
            value = verify_authorization(
                args.output, args.technical_promotion, args.release_root,
                args.release_observation, args.route_observation,
                args.rollback_release_root, args.rollback_observation, now,
            )
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web release authorization failed: {error}") from None
    print(json.dumps(value, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
