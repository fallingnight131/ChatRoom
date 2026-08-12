#!/usr/bin/env python3
"""Build a fail-closed Web technical-promotion record from independent observations."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError
from web_application_route_probe import read_route_observation
from web_release_probe import read_observation


PROMOTION_KEYS = {
    "schemaVersion", "evidenceType", "status", "baseUrl", "releaseId", "version",
    "sourceRevision", "rollbackReleaseId", "releaseObservationSha256",
    "routeObservationSha256", "rollbackObservationSha256", "artifactManifestSha256",
    "rollbackArtifactManifestSha256", "releaseObservedAt", "routesObservedAt",
    "rollbackLastObservedAt", "approvedAt", "maximumObservationAgeSeconds",
}


def _read_time(value: object, label: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(str(value))
    except ValueError as error:
        raise ManifestError(f"{label} is malformed") from error
    if parsed.tzinfo is None:
        raise ManifestError(f"{label} must include a timezone")
    return parsed.astimezone(timezone.utc)


def _digest(path: Path) -> str:
    if not path.is_file() or path.is_symlink():
        raise ManifestError("Web promotion input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_promotion_evidence(
    release_root: Path,
    release_observation_path: Path,
    route_observation_path: Path,
    rollback_release_root: Path,
    rollback_observation_path: Path,
    now_utc: datetime | None = None,
    maximum_age_seconds: int = 900,
) -> dict[str, object]:
    if not 60 <= maximum_age_seconds <= 3600:
        raise ManifestError("Web promotion observation age must be between 60 and 3600 seconds")
    now = now_utc or datetime.now(timezone.utc)
    if now.tzinfo is None:
        raise ManifestError("Web promotion clock must include a timezone")
    now = now.astimezone(timezone.utc)

    release = read_observation(release_observation_path, release_root)
    routes = read_route_observation(route_observation_path)
    rollback = read_observation(rollback_observation_path, rollback_release_root)
    if release["baseUrl"] != routes["baseUrl"] or release["baseUrl"] != rollback["baseUrl"]:
        raise ManifestError("Web promotion evidence must refer to one HTTPS origin")
    if release["releaseId"] == rollback["releaseId"]:
        raise ManifestError("Web promotion rollback target must be a different release")

    release_time = _read_time(release["observedAt"], "Web release observation time")
    route_time = _read_time(routes["observedAt"], "Web route observation time")
    rollback_time = _read_time(rollback["observedAt"], "Web rollback observation time")
    maximum_age = timedelta(seconds=maximum_age_seconds)
    for label, observed in (("release", release_time), ("route", route_time)):
        age = now - observed
        if age < timedelta(0) or age > maximum_age:
            raise ManifestError(f"Web {label} observation is stale or from the future")
    if abs(release_time - route_time) > timedelta(minutes=5):
        raise ManifestError("Web release and route observations are outside one promotion window")
    if rollback_time > now:
        raise ManifestError("Web rollback observation is from the future")

    return {
        "schemaVersion": 1,
        "evidenceType": "web-release-technical-promotion",
        "status": "technical-gates-observed-not-published",
        "baseUrl": release["baseUrl"],
        "releaseId": release["releaseId"],
        "version": release["version"],
        "sourceRevision": release["sourceRevision"],
        "rollbackReleaseId": rollback["releaseId"],
        "releaseObservationSha256": _digest(release_observation_path),
        "routeObservationSha256": _digest(route_observation_path),
        "rollbackObservationSha256": _digest(rollback_observation_path),
        "artifactManifestSha256": release["artifactManifestSha256"],
        "rollbackArtifactManifestSha256": rollback["artifactManifestSha256"],
        "releaseObservedAt": release["observedAt"],
        "routesObservedAt": routes["observedAt"],
        "rollbackLastObservedAt": rollback["observedAt"],
        "approvedAt": now.isoformat(),
        "maximumObservationAgeSeconds": maximum_age_seconds,
    }


def write_promotion_once(path: Path, evidence: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(evidence, ensure_ascii=True, indent=2, sort_keys=True) + "\n"
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", newline="\n", dir=path.parent, delete=False
        ) as stream:
            stream.write(rendered)
            stream.flush()
            os.fsync(stream.fileno())
            temporary = Path(stream.name)
        try:
            os.link(temporary, path)
        except FileExistsError as error:
            raise ManifestError("Web promotion evidence already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_promotion_evidence(
    evidence_path: Path,
    release_root: Path,
    release_observation_path: Path,
    route_observation_path: Path,
    rollback_release_root: Path,
    rollback_observation_path: Path,
) -> dict[str, object]:
    try:
        recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        raise ManifestError("Web promotion evidence is unreadable") from error
    if not isinstance(recorded, dict) or set(recorded) != PROMOTION_KEYS:
        raise ManifestError("Web promotion evidence has an unsupported shape")
    approved_at = _read_time(recorded.get("approvedAt"), "Web promotion approval time")
    maximum_age = recorded.get("maximumObservationAgeSeconds")
    if type(maximum_age) is not int:
        raise ManifestError("Web promotion observation age is malformed")
    expected = build_promotion_evidence(
        release_root,
        release_observation_path,
        route_observation_path,
        rollback_release_root,
        rollback_observation_path,
        approved_at,
        maximum_age,
    )
    if recorded != expected:
        raise ManifestError("Web promotion evidence does not match its bound inputs")
    return recorded


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("record", "verify"))
    parser.add_argument("--release-root", type=Path, required=True)
    parser.add_argument("--release-observation", type=Path, required=True)
    parser.add_argument("--route-observation", type=Path, required=True)
    parser.add_argument("--rollback-release-root", type=Path, required=True)
    parser.add_argument("--rollback-observation", type=Path, required=True)
    parser.add_argument("--maximum-age-seconds", type=int, default=900)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "record":
            evidence = build_promotion_evidence(
                args.release_root, args.release_observation, args.route_observation,
                args.rollback_release_root, args.rollback_observation,
                maximum_age_seconds=args.maximum_age_seconds,
            )
            write_promotion_once(args.output, evidence)
        else:
            evidence = verify_promotion_evidence(
                args.output, args.release_root, args.release_observation,
                args.route_observation, args.rollback_release_root,
                args.rollback_observation,
            )
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web promotion evidence failed: {error}") from None
    print(json.dumps(evidence, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
