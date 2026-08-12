#!/usr/bin/env python3
"""Close all Windows support-host records into one immutable matrix result."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError, read_version
from windows_support_host_evidence import verify_host_evidence


TARGETS = ("windows-10-22h2", "windows-11-23h2", "windows-11-24h2")
ROOT_KEYS = {
    "schemaVersion", "evidenceType", "status", "product", "architecture",
    "currentVersion", "currentSourceRevision", "previousVersion",
    "previousSourceRevision", "channel", "qtVersion",
    "expectedSignerCertificateSha256", "targets", "completedAt",
}
TARGET_KEYS = {"targetId", "osCaption", "osVersion", "osBuild", "evidenceSha256"}


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Windows support matrix input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_completion(
    evidence_paths: dict[str, Path],
    policy_path: Path,
    current_root: Path,
    current_version_file: Path,
    current_source_revision: str,
    previous_root: Path,
    previous_version_file: Path,
    previous_source_revision: str,
    channel: str,
    qt_version: str,
    expected_signer_sha256: str,
    now_utc: datetime,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows support matrix clock must be exact UTC")
    if set(evidence_paths) != set(TARGETS):
        raise ManifestError("Windows support matrix evidence set is incomplete")
    targets = []
    for target_id in TARGETS:
        path = evidence_paths[target_id]
        evidence = verify_host_evidence(
            path, policy_path, target_id, current_root, current_version_file,
            current_source_revision, previous_root, previous_version_file,
            previous_source_revision, channel, qt_version,
            expected_signer_sha256, now_utc)
        targets.append({
            "targetId": target_id,
            "osCaption": evidence["osCaption"],
            "osVersion": evidence["osVersion"],
            "osBuild": evidence["osBuild"],
            "evidenceSha256": _digest(path),
        })
    return {
        "schemaVersion": 1,
        "evidenceType": "windows-client-support-matrix-completion",
        "status": "all-supported-windows-client-targets-observed",
        "product": "chat-room-windows-client",
        "architecture": "x86_64",
        "currentVersion": read_version(current_version_file),
        "currentSourceRevision": current_source_revision,
        "previousVersion": read_version(previous_version_file),
        "previousSourceRevision": previous_source_revision,
        "channel": channel,
        "qtVersion": qt_version,
        "expectedSignerCertificateSha256": expected_signer_sha256,
        "targets": targets,
        "completedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if not path.is_absolute() or path.exists() or path.is_symlink():
        raise ManifestError("Windows support matrix output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Windows support matrix output directory is unsafe")
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
        raise ManifestError("Windows support matrix output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _read(path: Path) -> dict[str, object]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ManifestError("Windows support matrix completion is unsafe")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Windows support matrix completion has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Windows support matrix completion is unreadable") from error
    if not isinstance(value, dict):
        raise ManifestError("Windows support matrix completion must be an object")
    return value


def verify_completion(path: Path, *inputs) -> dict[str, object]:
    value = _read(path)
    targets = value.get("targets")
    if (set(value) != ROOT_KEYS or not isinstance(targets, list)
            or len(targets) != len(TARGETS)
            or any(not isinstance(item, dict) or set(item) != TARGET_KEYS
                   for item in targets)):
        raise ManifestError("Windows support matrix completion shape is invalid")
    try:
        completed = datetime.strptime(
            str(value["completedAt"]), "%Y-%m-%dT%H:%M:%SZ").replace(
                tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError("Windows support matrix completion time is invalid") from error
    expected = build_completion(*inputs, completed)
    if value != expected:
        raise ManifestError("Windows support matrix completion differs from inputs")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("record", "verify"))
    for target in TARGETS:
        parser.add_argument(f"--{target}-evidence", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--current-candidate-root", type=Path, required=True)
    parser.add_argument("--current-version-file", type=Path, required=True)
    parser.add_argument("--current-source-revision", required=True)
    parser.add_argument("--previous-candidate-root", type=Path, required=True)
    parser.add_argument("--previous-version-file", type=Path, required=True)
    parser.add_argument("--previous-source-revision", required=True)
    parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    parser.add_argument("--qt-version", required=True)
    parser.add_argument("--expected-signer-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    evidence = {
        target: getattr(args, target.replace("-", "_") + "_evidence")
        for target in TARGETS
    }
    inputs = (
        evidence, args.policy, args.current_candidate_root,
        args.current_version_file, args.current_source_revision,
        args.previous_candidate_root, args.previous_version_file,
        args.previous_source_revision, args.channel, args.qt_version,
        args.expected_signer_sha256,
    )
    try:
        if args.command == "record":
            result = build_completion(
                *inputs, datetime.now(timezone.utc).replace(microsecond=0))
            write_once(args.output.resolve(strict=False), result)
        else:
            result = verify_completion(args.output, *inputs)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows support matrix completion failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
