#!/usr/bin/env python3
"""Bind restored Web static and route observations to rollback execution."""

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
from web_release_rollback_execution import verify_rollback


KEYS = {
    "schemaVersion", "evidenceType", "status", "baseUrl", "failedReleaseId",
    "restoredReleaseId", "rollbackExecutionSha256",
    "restoredReleaseObservationSha256", "restoredRouteObservationSha256",
    "rollbackExecutedAt", "releaseObservedAt", "routesObservedAt",
    "completedAt", "maximumCompletionSeconds",
}


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Web rollback completion input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _time(value: object, label: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError as error:
        raise ManifestError(f"Web rollback completion {label} is invalid") from error
    if parsed.tzinfo is None:
        raise ManifestError(f"Web rollback completion {label} needs timezone")
    return parsed.astimezone(timezone.utc)


def build_completion(
    rollback_execution_path: Path,
    execution_path: Path,
    authorization_path: Path,
    technical_promotion_path: Path,
    release_root: Path,
    pre_release_observation: Path,
    pre_route_observation: Path,
    rollback_release_root: Path,
    rollback_observation: Path,
    restored_release_observation: Path,
    restored_route_observation: Path,
    now_utc: datetime,
    maximum_completion_seconds: int = 600,
) -> dict[str, object]:
    if not 60 <= maximum_completion_seconds <= 900:
        raise ManifestError("Web rollback completion window must be 60 to 900 seconds")
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Web rollback completion clock must be exact UTC")
    rollback = verify_rollback(
        rollback_execution_path, execution_path, authorization_path,
        technical_promotion_path, release_root, pre_release_observation,
        pre_route_observation, rollback_release_root, rollback_observation)
    release = read_observation(restored_release_observation, rollback_release_root)
    routes = read_route_observation(restored_route_observation)
    if (release["baseUrl"] != rollback["baseUrl"]
            or routes["baseUrl"] != rollback["baseUrl"]
            or release["releaseId"] != rollback["restoredReleaseId"]):
        raise ManifestError("Web restored observations differ from rollback execution")
    rolled_back = _time(rollback["rollbackExecutedAt"], "execution time")
    release_time = _time(release["observedAt"], "release observation time")
    route_time = _time(routes["observedAt"], "route observation time")
    deadline = rolled_back + timedelta(seconds=maximum_completion_seconds)
    if (release_time < rolled_back or route_time < rolled_back
            or release_time > deadline or route_time > deadline
            or now_utc > deadline
            or release_time > now_utc + timedelta(minutes=1)
            or route_time > now_utc + timedelta(minutes=1)
            or abs(release_time - route_time) > timedelta(minutes=5)):
        raise ManifestError("Web restored observations are outside rollback window")
    return {
        "schemaVersion": 1,
        "evidenceType": "web-production-rollback-completion",
        "status": "production-rollback-observed",
        "baseUrl": rollback["baseUrl"],
        "failedReleaseId": rollback["failedReleaseId"],
        "restoredReleaseId": rollback["restoredReleaseId"],
        "rollbackExecutionSha256": _digest(rollback_execution_path),
        "restoredReleaseObservationSha256": _digest(restored_release_observation),
        "restoredRouteObservationSha256": _digest(restored_route_observation),
        "rollbackExecutedAt": rollback["rollbackExecutedAt"],
        "releaseObservedAt": release["observedAt"],
        "routesObservedAt": routes["observedAt"],
        "completedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "maximumCompletionSeconds": maximum_completion_seconds,
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if not path.is_absolute() or path.exists() or path.is_symlink():
        raise ManifestError("Web rollback completion output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Web rollback completion output directory is unsafe")
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", newline="\n", dir=path.parent, delete=False,
        ) as stream:
            stream.write(json.dumps(value, ensure_ascii=True, indent=2,
                                    sort_keys=True) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
            temporary = Path(stream.name)
        os.link(temporary, path)
    except FileExistsError as error:
        raise ManifestError("Web rollback completion output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_completion(path: Path, *inputs) -> dict[str, object]:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Web rollback completion must be a regular file")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Web rollback completion has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Web rollback completion is unreadable") from error
    if (not isinstance(value, dict) or set(value) != KEYS
            or type(value.get("maximumCompletionSeconds")) is not int):
        raise ManifestError("Web rollback completion shape is invalid")
    completed = _time(value["completedAt"], "completion time")
    expected = build_completion(
        *inputs, completed.replace(microsecond=0),
        int(value["maximumCompletionSeconds"]))
    if value != expected:
        raise ManifestError("Web rollback completion differs from inputs")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("record", "verify"))
    for name in (
        "rollback-execution", "execution", "authorization", "technical-promotion",
        "release-root", "pre-release-observation", "pre-route-observation",
        "rollback-release-root", "rollback-observation",
        "restored-release-observation", "restored-route-observation", "output",
    ):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--maximum-completion-seconds", type=int, default=600)
    args = parser.parse_args()
    values = (
        args.rollback_execution, args.execution, args.authorization,
        args.technical_promotion, args.release_root, args.pre_release_observation,
        args.pre_route_observation, args.rollback_release_root,
        args.rollback_observation, args.restored_release_observation,
        args.restored_route_observation,
    )
    try:
        if args.command == "record":
            result = build_completion(
                *values, datetime.now(timezone.utc).replace(microsecond=0),
                args.maximum_completion_seconds)
            write_once(args.output.resolve(strict=False), result)
        else:
            result = verify_completion(args.output, *values)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web rollback completion failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
