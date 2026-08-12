#!/usr/bin/env python3
"""Stage immutable Web artifacts and atomically activate or roll them back."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

from artifact_manifest_common import ManifestError, atomic_write, payload_files, sha256_file
from web_artifact_manifest import read_response_policy


WEB_VERSION = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"
)
SOURCE_REVISION = re.compile(r"^[0-9a-f]{40}$")


def _read_json(path: Path, label: str) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        raise ManifestError(f"{label} is unreadable") from error
    if not isinstance(value, dict):
        raise ManifestError(f"{label} must be a JSON object")
    return value


def _safe_relative_path(value: object) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        raise ManifestError("Web release contains an unsafe file path")
    path = PurePosixPath(value)
    if (path.is_absolute() or path.as_posix() != value
            or any(part in {"", ".", ".."} for part in path.parts)):
        raise ManifestError("Web release contains an unsafe file path")
    return path.as_posix()


def _read_checksums(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise ManifestError("Web artifact checksums are unreadable") from error
    checksums: dict[str, str] = {}
    for line in lines:
        parts = line.split("  ", 1)
        if len(parts) != 2 or len(parts[0]) != 64 or any(character not in "0123456789abcdef" for character in parts[0]):
            raise ManifestError("Web artifact checksums are malformed")
        relative = _safe_relative_path(parts[1])
        if relative in checksums:
            raise ManifestError("Web artifact checksums contain a duplicate path")
        checksums[relative] = parts[0]
    if not checksums:
        raise ManifestError("Web artifact checksums are empty")
    return checksums


def validate_release(release_root: Path) -> dict[str, object]:
    if not release_root.is_dir() or release_root.is_symlink():
        raise ManifestError("Web release root must be a real directory")
    manifest = _read_json(release_root / "web-artifact-manifest.json", "Web artifact manifest")
    if (manifest.get("schemaVersion") != 2
            or manifest.get("product") != "chat-room-web-client"
            or manifest.get("releaseStatus") != "unsigned-not-deployed-verification-only"):
        raise ManifestError("Web artifact manifest is not a supported schema-2 verification artifact")

    version = manifest.get("version")
    revision = manifest.get("sourceRevision")
    if (not isinstance(version, str) or not WEB_VERSION.fullmatch(version)
            or not isinstance(revision, str) or not SOURCE_REVISION.fullmatch(revision)):
        raise ManifestError("Web artifact release identity is missing")
    release_id = f"{version}-{revision}"
    if any(character not in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz.-" for character in release_id):
        raise ManifestError("Web artifact release identity is unsafe")

    entries = manifest.get("files")
    policy = manifest.get("responsePolicy")
    if not isinstance(entries, list) or not isinstance(policy, dict):
        raise ManifestError("Web artifact manifest payload metadata is missing")
    declared: dict[str, tuple[str, int]] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise ManifestError("Web artifact file entry is malformed")
        relative = _safe_relative_path(entry.get("path"))
        digest, size = entry.get("sha256"), entry.get("size")
        if (relative in declared or not isinstance(digest, str) or len(digest) != 64
                or any(character not in "0123456789abcdef" for character in digest)
                or not isinstance(size, int) or size < 0):
            raise ManifestError("Web artifact file entry is malformed")
        declared[relative] = (digest, size)

    policy_path = _safe_relative_path(policy.get("path"))
    if policy_path != "response-policy.json" or policy.get("applicationStatus") != "required-not-observed":
        raise ManifestError("Web response policy metadata is not deployable")
    policy_digest, policy_size = policy.get("sha256"), policy.get("size")
    if (not isinstance(policy_digest, str) or len(policy_digest) != 64
            or any(character not in "0123456789abcdef" for character in policy_digest)
            or not isinstance(policy_size, int) or policy_size < 0):
        raise ManifestError("Web response policy identity is malformed")
    if policy.get("requiredScheme") != "https":
        raise ManifestError("Web response policy does not require HTTPS")
    declared[policy_path] = (policy_digest, policy_size)

    actual_files = {
        path.relative_to(release_root).as_posix()
        for path in payload_files(release_root)
    }
    expected_files = set(declared) | {"SHA256SUMS", "web-artifact-manifest.json"}
    if actual_files != expected_files:
        raise ManifestError("Web artifact contains undeclared or missing files")

    checksums = _read_checksums(release_root / "SHA256SUMS")
    if set(checksums) != set(declared):
        raise ManifestError("Web artifact checksums do not match declared payload paths")
    for relative, (expected_digest, expected_size) in declared.items():
        digest, size = sha256_file(release_root / relative)
        if digest != expected_digest or size != expected_size or checksums[relative] != digest:
            raise ManifestError("Web artifact payload integrity check failed")
    read_response_policy(release_root / policy_path)

    return {
        "releaseId": release_id,
        "version": version,
        "sourceRevision": revision,
        "responsePolicySha256": policy_digest,
        "entrypoint": manifest.get("entrypoint"),
        "fileCount": len(entries),
    }


def stage_release(artifact_root: Path, store_root: Path) -> dict[str, object]:
    identity = validate_release(artifact_root)
    if identity["entrypoint"] != "site/index.html":
        raise ManifestError("Web release entrypoint is unsupported")
    releases = store_root / "releases"
    releases.mkdir(parents=True, exist_ok=True)
    destination = releases / str(identity["releaseId"])
    if destination.exists():
        existing = validate_release(destination)
        if existing != identity:
            raise ManifestError("Immutable Web release identity already exists with different content")
        return {**identity, "stageStatus": "already-present"}

    temporary = Path(tempfile.mkdtemp(prefix=".staging-", dir=releases))
    try:
        for source in payload_files(artifact_root):
            relative = source.relative_to(artifact_root)
            target = temporary / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        if validate_release(temporary) != identity:
            raise ManifestError("Staged Web release identity changed during copy")
        os.rename(temporary, destination)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    return {**identity, "stageStatus": "staged"}


def activate_release(store_root: Path, release_id: str, activated_at: str | None = None) -> dict[str, object]:
    if (not release_id or release_id in {".", ".."} or "/" in release_id or "\\" in release_id):
        raise ManifestError("Web release ID is unsafe")
    identity = validate_release(store_root / "releases" / release_id)
    if identity["releaseId"] != release_id:
        raise ManifestError("Web release directory does not match its identity")
    pointer = {
        "schemaVersion": 1,
        **identity,
        "activatedAt": activated_at or datetime.now(timezone.utc).isoformat(),
    }
    atomic_write(
        store_root / "active-release.json",
        json.dumps(pointer, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
    )
    return pointer


def inspect_active_release(store_root: Path) -> dict[str, object]:
    pointer = _read_json(store_root / "active-release.json", "Active Web release pointer")
    if (set(pointer) != {
            "schemaVersion", "releaseId", "version", "sourceRevision",
            "responsePolicySha256", "entrypoint", "fileCount", "activatedAt",
        } or pointer.get("schemaVersion") != 1
            or not isinstance(pointer.get("releaseId"), str)
            or not isinstance(pointer.get("activatedAt"), str)):
        raise ManifestError("Active Web release pointer has an unsupported shape")
    identity = validate_release(store_root / "releases" / str(pointer["releaseId"]))
    for key, value in identity.items():
        if pointer.get(key) != value:
            raise ManifestError("Active Web release pointer does not match immutable release")
    return {"status": "healthy", **pointer}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    stage = subparsers.add_parser("stage")
    stage.add_argument("--artifact-root", type=Path, required=True)
    stage.add_argument("--store-root", type=Path, required=True)
    activate = subparsers.add_parser("activate")
    activate.add_argument("--store-root", type=Path, required=True)
    activate.add_argument("--release-id", required=True)
    status = subparsers.add_parser("status")
    status.add_argument("--store-root", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "stage":
            result = stage_release(args.artifact_root, args.store_root)
        elif args.command == "activate":
            result = activate_release(args.store_root, args.release_id)
        else:
            result = inspect_active_release(args.store_root)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web release store failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
