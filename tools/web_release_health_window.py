#!/usr/bin/env python3
"""Bind repeated Web static and route observations into one health window."""

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


ROOT_KEYS = {
    "schemaVersion", "evidenceType", "status", "phase", "baseUrl",
    "releaseId", "version", "sourceRevision", "sampleCount", "samples",
    "startedAt", "endedAt", "completedAt", "minimumDurationSeconds",
    "maximumGapSeconds",
}
SAMPLE_KEYS = {
    "index", "releaseObservationSha256", "routeObservationSha256",
    "releaseObservedAt", "routesObservedAt",
}


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Web health-window input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _time(value: object, label: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError as error:
        raise ManifestError(f"Web health-window {label} is invalid") from error
    if parsed.tzinfo is None:
        raise ManifestError(f"Web health-window {label} must include a timezone")
    return parsed.astimezone(timezone.utc)


def build_window(
    release_observations: list[Path],
    route_observations: list[Path],
    release_root: Path,
    phase: str,
    now_utc: datetime,
    minimum_duration_seconds: int = 60,
    maximum_gap_seconds: int = 300,
) -> dict[str, object]:
    if phase not in {"preview", "production"}:
        raise ManifestError("Web health-window phase is invalid")
    if (not 60 <= minimum_duration_seconds <= 900
            or not 15 <= maximum_gap_seconds <= 300):
        raise ManifestError("Web health-window duration or gap policy is invalid")
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Web health-window clock must be an exact UTC second")
    if (len(release_observations) != len(route_observations)
            or not 3 <= len(release_observations) <= 30
            or len(set(release_observations)) != len(release_observations)
            or len(set(route_observations)) != len(route_observations)):
        raise ManifestError("Web health-window requires 3 to 30 unique observation pairs")

    samples = []
    identity: dict[str, object] | None = None
    sample_times: list[datetime] = []
    seen_digests: set[str] = set()
    for index, (release_path, route_path) in enumerate(
            zip(release_observations, route_observations)):
        release = read_observation(release_path, release_root)
        routes = read_route_observation(route_path)
        if (routes["baseUrl"] != release["baseUrl"]
                or (identity is not None and any(release[key] != identity[key]
                    for key in ("baseUrl", "releaseId", "version", "sourceRevision")))):
            raise ManifestError("Web health-window samples do not share one release origin")
        if identity is None:
            identity = release
        release_time = _time(release["observedAt"], "release observation time")
        route_time = _time(routes["observedAt"], "route observation time")
        if abs(release_time - route_time) > timedelta(seconds=30):
            raise ManifestError("Web health-window observation pair is too far apart")
        sample_time = max(release_time, route_time)
        if sample_times and sample_time <= sample_times[-1]:
            raise ManifestError("Web health-window samples are not strictly ordered")
        release_sha, route_sha = _digest(release_path), _digest(route_path)
        if release_sha in seen_digests or route_sha in seen_digests:
            raise ManifestError("Web health-window reuses observation evidence")
        seen_digests.update((release_sha, route_sha))
        sample_times.append(sample_time)
        samples.append({
            "index": index,
            "releaseObservationSha256": release_sha,
            "routeObservationSha256": route_sha,
            "releaseObservedAt": release["observedAt"],
            "routesObservedAt": routes["observedAt"],
        })

    assert identity is not None
    started, ended = sample_times[0], sample_times[-1]
    if (ended - started < timedelta(seconds=minimum_duration_seconds)
            or ended - started > timedelta(minutes=15)
            or any(later - earlier > timedelta(seconds=maximum_gap_seconds)
                   for earlier, later in zip(sample_times, sample_times[1:]))
            or ended > now_utc + timedelta(minutes=1)
            or now_utc > ended + timedelta(minutes=5)):
        raise ManifestError("Web health-window samples are outside the bounded window")
    return {
        "schemaVersion": 1,
        "evidenceType": "web-release-health-window",
        "status": "sustained-healthy",
        "phase": phase,
        "baseUrl": identity["baseUrl"],
        "releaseId": identity["releaseId"],
        "version": identity["version"],
        "sourceRevision": identity["sourceRevision"],
        "sampleCount": len(samples),
        "samples": samples,
        "startedAt": started.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "endedAt": ended.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "completedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "minimumDurationSeconds": minimum_duration_seconds,
        "maximumGapSeconds": maximum_gap_seconds,
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if not path.is_absolute() or path.exists() or path.is_symlink():
        raise ManifestError("Web health-window output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Web health-window output directory is unsafe")
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
        raise ManifestError("Web health-window output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _read(path: Path) -> dict[str, object]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ManifestError("Web health-window evidence is unsafe")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Web health-window evidence has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Web health-window evidence is unreadable") from error
    if not isinstance(value, dict):
        raise ManifestError("Web health-window evidence must be an object")
    return value


def verify_window(path: Path, *inputs) -> dict[str, object]:
    value = _read(path)
    samples = value.get("samples")
    if (set(value) != ROOT_KEYS or not isinstance(samples, list)
            or any(not isinstance(sample, dict) or set(sample) != SAMPLE_KEYS
                   for sample in samples)):
        raise ManifestError("Web health-window evidence shape is invalid")
    completed = _time(value.get("completedAt"), "completion time")
    minimum = value.get("minimumDurationSeconds")
    gap = value.get("maximumGapSeconds")
    if type(minimum) is not int or type(gap) is not int:
        raise ManifestError("Web health-window policy fields are invalid")
    expected = build_window(*inputs, completed.replace(microsecond=0), minimum, gap)
    if value != expected:
        raise ManifestError("Web health-window evidence differs from inputs")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("record", "verify"))
    parser.add_argument("--release-observation", type=Path, action="append", required=True)
    parser.add_argument("--route-observation", type=Path, action="append", required=True)
    parser.add_argument("--release-root", type=Path, required=True)
    parser.add_argument("--phase", choices=("preview", "production"), required=True)
    parser.add_argument("--minimum-duration-seconds", type=int, default=60)
    parser.add_argument("--maximum-gap-seconds", type=int, default=300)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    inputs = (args.release_observation, args.route_observation,
              args.release_root, args.phase)
    try:
        if args.command == "record":
            result = build_window(
                *inputs, datetime.now(timezone.utc).replace(microsecond=0),
                args.minimum_duration_seconds, args.maximum_gap_seconds)
            write_once(args.output.resolve(strict=False), result)
        else:
            result = verify_window(args.output, *inputs)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web health-window evidence failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
