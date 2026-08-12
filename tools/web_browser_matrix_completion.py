#!/usr/bin/env python3
"""Close six branded-browser records into one immutable Web support result."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError
from web_browser_host_evidence import verify_host_evidence
from web_release_store import validate_release


TARGETS = (
    "chrome-current", "chrome-previous", "edge-current", "edge-previous",
    "firefox-current", "firefox-previous",
)
ROOT_KEYS = {
    "schemaVersion", "evidenceType", "status", "product", "releaseId",
    "version", "sourceRevision", "artifactManifestSha256", "targets",
    "completedAt",
}
TARGET_KEYS = {
    "targetId", "browserFamily", "browserProduct", "supportPosition",
    "browserVersion", "browserExecutableSha256", "platform", "architecture",
    "evidenceSha256",
}


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Web browser matrix input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _version(value: str) -> tuple[int, ...]:
    try:
        parts = tuple(int(part) for part in value.split("."))
    except ValueError as error:
        raise ManifestError("Web browser matrix version is invalid") from error
    if len(parts) < 2 or len(parts) > 4:
        raise ManifestError("Web browser matrix version is invalid")
    return parts


def build_completion(
    evidence_paths: dict[str, Path],
    expectations: dict[str, tuple[str, str]],
    policy_path: Path,
    release_root: Path,
    now_utc: datetime,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Web browser matrix clock must be exact UTC")
    if set(evidence_paths) != set(TARGETS) or set(expectations) != set(TARGETS):
        raise ManifestError("Web browser matrix evidence or expectation set is incomplete")
    for family in ("chrome", "edge", "firefox"):
        current = expectations[f"{family}-current"][0]
        previous = expectations[f"{family}-previous"][0]
        if _version(current) <= _version(previous):
            raise ManifestError("Current browser version must be newer than previous")

    identity = validate_release(release_root)
    manifest_sha = _digest(release_root / "web-artifact-manifest.json")
    targets = []
    for target_id in TARGETS:
        expected_version, expected_sha = expectations[target_id]
        path = evidence_paths[target_id]
        evidence = verify_host_evidence(
            path, policy_path, target_id, release_root, expected_version,
            expected_sha, now_utc)
        targets.append({
            "targetId": target_id,
            "browserFamily": evidence["browserFamily"],
            "browserProduct": evidence["browserProduct"],
            "supportPosition": evidence["supportPosition"],
            "browserVersion": evidence["browserVersion"],
            "browserExecutableSha256": evidence["browserExecutableSha256"],
            "platform": evidence["platform"],
            "architecture": evidence["architecture"],
            "evidenceSha256": _digest(path),
        })
    return {
        "schemaVersion": 1,
        "evidenceType": "web-browser-support-matrix-completion",
        "status": "all-six-branded-browser-targets-observed",
        "product": "chat-room-web-client",
        "releaseId": identity["releaseId"],
        "version": identity["version"],
        "sourceRevision": identity["sourceRevision"],
        "artifactManifestSha256": manifest_sha,
        "targets": targets,
        "completedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if not path.is_absolute() or path.exists() or path.is_symlink():
        raise ManifestError("Web browser matrix output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Web browser matrix output directory is unsafe")
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
        raise ManifestError("Web browser matrix output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _read(path: Path) -> dict[str, object]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ManifestError("Web browser matrix completion is unsafe")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Web browser matrix completion has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Web browser matrix completion is unreadable") from error
    if not isinstance(value, dict):
        raise ManifestError("Web browser matrix completion must be an object")
    return value


def verify_completion(path: Path, *inputs) -> dict[str, object]:
    value = _read(path)
    targets = value.get("targets")
    if (set(value) != ROOT_KEYS or not isinstance(targets, list)
            or len(targets) != len(TARGETS)
            or any(not isinstance(item, dict) or set(item) != TARGET_KEYS
                   for item in targets)):
        raise ManifestError("Web browser matrix completion shape is invalid")
    try:
        completed = datetime.strptime(
            str(value["completedAt"]), "%Y-%m-%dT%H:%M:%SZ"
        ).replace(tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError("Web browser matrix completion time is invalid") from error
    expected = build_completion(*inputs, completed)
    if value != expected:
        raise ManifestError("Web browser matrix completion differs from inputs")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("record", "verify"))
    for target in TARGETS:
        parser.add_argument(f"--{target}-evidence", type=Path, required=True)
        parser.add_argument(f"--{target}-version", required=True)
        parser.add_argument(f"--{target}-sha256", required=True)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--release-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    evidence = {
        target: getattr(args, target.replace("-", "_") + "_evidence")
        for target in TARGETS
    }
    expectations = {
        target: (
            getattr(args, target.replace("-", "_") + "_version"),
            getattr(args, target.replace("-", "_") + "_sha256"),
        )
        for target in TARGETS
    }
    inputs = (evidence, expectations, args.policy, args.release_root)
    try:
        if args.command == "record":
            result = build_completion(
                *inputs, datetime.now(timezone.utc).replace(microsecond=0))
            write_once(args.output.resolve(strict=False), result)
        else:
            result = verify_completion(args.output, *inputs)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web browser matrix completion failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
