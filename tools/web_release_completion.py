#!/usr/bin/env python3
"""Bind post-switch Web observations into durable production-promotion evidence."""

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
from web_release_execution import verify_execution
from web_release_probe import read_observation


KEYS = {
    "schemaVersion", "evidenceType", "status", "baseUrl", "releaseId",
    "rollbackReleaseId", "version", "sourceRevision", "executionSha256",
    "postSwitchReleaseObservationSha256", "postSwitchRouteObservationSha256",
    "executedAt", "releaseObservedAt", "routesObservedAt", "completedAt",
    "maximumCompletionSeconds",
}


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Web release completion input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _time(value: object, label: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError as error:
        raise ManifestError(f"Web release completion {label} is invalid") from error
    if parsed.tzinfo is None:
        raise ManifestError(f"Web release completion {label} must include a timezone")
    return parsed.astimezone(timezone.utc)


def build_completion(
    execution_path: Path,
    authorization_path: Path,
    technical_promotion_path: Path,
    release_root: Path,
    pre_release_observation: Path,
    pre_route_observation: Path,
    rollback_release_root: Path,
    rollback_observation: Path,
    post_release_observation: Path,
    post_route_observation: Path,
    now_utc: datetime,
    maximum_completion_seconds: int = 600,
) -> dict[str, object]:
    if not 60 <= maximum_completion_seconds <= 900:
        raise ManifestError("Web release completion window must be 60 to 900 seconds")
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Web release completion clock must be an exact UTC second")
    execution = verify_execution(
        execution_path, authorization_path, technical_promotion_path,
        release_root, pre_release_observation, pre_route_observation,
        rollback_release_root, rollback_observation,
    )
    release = read_observation(post_release_observation, release_root)
    routes = read_route_observation(post_route_observation)
    if (release["baseUrl"] != execution["baseUrl"]
            or routes["baseUrl"] != execution["baseUrl"]
            or release["releaseId"] != execution["releaseId"]
            or release["version"] != execution["version"]
            or release["sourceRevision"] != execution["sourceRevision"]):
        raise ManifestError("Web post-switch observations do not match execution identity")
    executed = _time(execution["executedAt"], "execution time")
    release_time = _time(release["observedAt"], "release observation time")
    route_time = _time(routes["observedAt"], "route observation time")
    deadline = executed + timedelta(seconds=maximum_completion_seconds)
    if (release_time < executed or route_time < executed
            or release_time > deadline or route_time > deadline
            or release_time > now_utc + timedelta(minutes=1)
            or route_time > now_utc + timedelta(minutes=1)
            or abs(release_time - route_time) > timedelta(minutes=5)
            or now_utc > deadline):
        raise ManifestError("Web post-switch observations are outside the completion window")
    return {
        "schemaVersion": 1,
        "evidenceType": "web-production-promotion-completion",
        "status": "production-promotion-observed",
        "baseUrl": execution["baseUrl"],
        "releaseId": execution["releaseId"],
        "rollbackReleaseId": execution["rollbackReleaseId"],
        "version": execution["version"],
        "sourceRevision": execution["sourceRevision"],
        "executionSha256": _digest(execution_path),
        "postSwitchReleaseObservationSha256": _digest(post_release_observation),
        "postSwitchRouteObservationSha256": _digest(post_route_observation),
        "executedAt": execution["executedAt"],
        "releaseObservedAt": release["observedAt"],
        "routesObservedAt": routes["observedAt"],
        "completedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "maximumCompletionSeconds": maximum_completion_seconds,
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if path.exists() or path.is_symlink() or not path.is_absolute():
        raise ManifestError("Web release completion output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Web release completion output directory is unsafe")
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
            raise ManifestError("Web release completion output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_completion(
    completion_path: Path,
    execution_path: Path,
    authorization_path: Path,
    technical_promotion_path: Path,
    release_root: Path,
    pre_release_observation: Path,
    pre_route_observation: Path,
    rollback_release_root: Path,
    rollback_observation: Path,
    post_release_observation: Path,
    post_route_observation: Path,
) -> dict[str, object]:
    if completion_path.is_symlink() or not completion_path.is_file():
        raise ManifestError("Web release completion evidence must be a regular file")

    def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Web release completion evidence has duplicate keys")
            result[key] = value
        return result

    try:
        recorded = json.loads(
            completion_path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Web release completion evidence is unreadable") from error
    if not isinstance(recorded, dict) or set(recorded) != KEYS:
        raise ManifestError("Web release completion evidence has an unsupported shape")
    maximum = recorded.get("maximumCompletionSeconds")
    if type(maximum) is not int:
        raise ManifestError("Web release completion window is malformed")
    completed = _time(recorded.get("completedAt"), "completion time")
    expected = build_completion(
        execution_path, authorization_path, technical_promotion_path,
        release_root, pre_release_observation, pre_route_observation,
        rollback_release_root, rollback_observation, post_release_observation,
        post_route_observation, completed.replace(microsecond=0), maximum,
    )
    if recorded != expected:
        raise ManifestError("Web release completion evidence does not match its inputs")
    return recorded


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("record", "verify"))
    for name in ("execution", "authorization", "technical-promotion",
                 "release-root", "pre-release-observation", "pre-route-observation",
                 "rollback-release-root", "rollback-observation",
                 "post-release-observation", "post-route-observation", "output"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--maximum-completion-seconds", type=int, default=600)
    args = parser.parse_args()
    now = datetime.now(timezone.utc).replace(microsecond=0)
    try:
        values = (
            args.execution, args.authorization, args.technical_promotion,
            args.release_root, args.pre_release_observation,
            args.pre_route_observation, args.rollback_release_root,
            args.rollback_observation, args.post_release_observation,
            args.post_route_observation,
        )
        if args.command == "record":
            result = build_completion(
                *values, now, args.maximum_completion_seconds)
            write_once(args.output.resolve(strict=False), result)
        else:
            result = verify_completion(args.output, *values)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web release completion failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
