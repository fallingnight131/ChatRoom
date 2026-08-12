#!/usr/bin/env python3
"""Close reviewed preview health, execution, and production health as one release."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError
from web_release_completion import verify_completion
from web_release_health_window import verify_window


KEYS = {
    "schemaVersion", "evidenceType", "status", "releaseId",
    "rollbackReleaseId", "version", "sourceRevision", "previewBaseUrl",
    "productionBaseUrl", "previewHealthSha256", "executionSha256",
    "productionHealthSha256", "promotionCompletionSha256", "completedAt",
}


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Web staged completion input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _time(value: object, label: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError as error:
        raise ManifestError(f"Web staged completion {label} is invalid") from error
    if parsed.tzinfo is None:
        raise ManifestError(f"Web staged completion {label} must include a timezone")
    return parsed.astimezone(timezone.utc)


def _paths(root: Path, stem: str) -> list[Path]:
    return [root / f"{stem}.json", root / f"{stem}-1.json", root / f"{stem}-2.json"]


def build_completion(
    evidence_root: Path,
    release_root: Path,
    rollback_release_root: Path,
    now_utc: datetime,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Web staged completion clock must be an exact UTC second")
    preview_release = _paths(evidence_root, "candidate-static-reviewed")
    preview_routes = _paths(evidence_root, "candidate-routes-reviewed")
    production_release = _paths(evidence_root, "post-static")
    production_routes = _paths(evidence_root, "post-routes")
    preview = verify_window(
        evidence_root / "candidate-preview-health-reviewed.json",
        preview_release, preview_routes, release_root, "preview")
    production = verify_window(
        evidence_root / "production-health.json",
        production_release, production_routes, release_root, "production")
    completion = verify_completion(
        evidence_root / "production-completion.json",
        evidence_root / "pointer-execution.json",
        evidence_root / "production-authorization.json",
        evidence_root / "technical-promotion-reviewed.json",
        release_root,
        preview_release[0], preview_routes[0], rollback_release_root,
        evidence_root / "rollback-static-reviewed.json",
        production_release[0], production_routes[0],
    )
    execution = json.loads(
        (evidence_root / "pointer-execution.json").read_text(encoding="utf-8"))
    if (preview["releaseId"] != completion["releaseId"]
            or production["releaseId"] != completion["releaseId"]
            or preview["version"] != completion["version"]
            or production["version"] != completion["version"]
            or preview["sourceRevision"] != completion["sourceRevision"]
            or production["sourceRevision"] != completion["sourceRevision"]
            or preview["baseUrl"] == production["baseUrl"]
            or production["baseUrl"] != completion["baseUrl"]
            or completion["rollbackReleaseId"] == completion["releaseId"]
            or _time(preview["endedAt"], "preview end")
                > _time(execution.get("executedAt"), "execution time")
            or _time(production["startedAt"], "production start")
                < _time(execution.get("executedAt"), "execution time")
            or _time(completion["completedAt"], "promotion completion time")
                < _time(production["endedAt"], "production end")
            or now_utc > _time(completion["completedAt"], "promotion completion time")
                + timedelta(minutes=5)):
        raise ManifestError("Web staged health and promotion identities or order differ")
    return {
        "schemaVersion": 1,
        "evidenceType": "web-staged-production-release-completion",
        "status": "preview-production-health-and-promotion-observed",
        "releaseId": completion["releaseId"],
        "rollbackReleaseId": completion["rollbackReleaseId"],
        "version": completion["version"],
        "sourceRevision": completion["sourceRevision"],
        "previewBaseUrl": preview["baseUrl"],
        "productionBaseUrl": production["baseUrl"],
        "previewHealthSha256": _digest(
            evidence_root / "candidate-preview-health-reviewed.json"),
        "executionSha256": _digest(evidence_root / "pointer-execution.json"),
        "productionHealthSha256": _digest(evidence_root / "production-health.json"),
        "promotionCompletionSha256": _digest(
            evidence_root / "production-completion.json"),
        "completedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if not path.is_absolute() or path.exists() or path.is_symlink():
        raise ManifestError("Web staged completion output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Web staged completion output directory is unsafe")
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
        raise ManifestError("Web staged completion output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_staged_completion(
    output: Path, evidence_root: Path, release_root: Path,
    rollback_release_root: Path,
) -> dict[str, object]:
    if output.is_symlink() or not output.is_file():
        raise ManifestError("Web staged completion must be a regular file")
    def unique(pairs):
        result = {}
        for key, item in pairs:
            if key in result:
                raise ManifestError("Web staged completion has duplicate keys")
            result[key] = item
        return result

    try:
        value = json.loads(
            output.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Web staged completion is unreadable") from error
    if not isinstance(value, dict) or set(value) != KEYS:
        raise ManifestError("Web staged completion shape is invalid")
    completed = _time(value.get("completedAt"), "completion time")
    expected = build_completion(
        evidence_root, release_root, rollback_release_root,
        completed.replace(microsecond=0))
    if value != expected:
        raise ManifestError("Web staged completion differs from its inputs")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("record", "verify"))
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--release-root", type=Path, required=True)
    parser.add_argument("--rollback-release-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "record":
            value = build_completion(
                args.evidence_root, args.release_root, args.rollback_release_root,
                datetime.now(timezone.utc).replace(microsecond=0))
            write_once(args.output.resolve(strict=False), value)
        else:
            value = verify_staged_completion(
                args.output, args.evidence_root, args.release_root,
                args.rollback_release_root)
    except (ManifestError, OSError, json.JSONDecodeError) as error:
        raise SystemExit(f"Web staged completion failed: {error}") from None
    print(json.dumps(value, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
