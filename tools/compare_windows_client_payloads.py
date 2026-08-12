#!/usr/bin/env python3
"""Create closed evidence that two deployed Windows client payloads are equivalent."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import tempfile


EXECUTABLES = {"ChatClient.exe", "ChatRoomUpdateLauncher.exe"}
FORBIDDEN_SUFFIXES = {".exp", ".ilk", ".lib", ".obj", ".pdb"}
VERSION = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
REVISION = re.compile(r"^[0-9a-f]{40}([0-9a-f]{24})?$")


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def inventory(root: Path) -> dict[str, dict[str, object]]:
    if not root.is_absolute() or not root.exists() or not root.is_dir() or root.is_symlink():
        raise ValueError("payload root must be an absolute regular directory")
    result: dict[str, dict[str, object]] = {}
    folded: set[str] = set()
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError("payload contains a symbolic link")
        if path.is_dir():
            continue
        if not path.is_file():
            raise ValueError("payload contains a non-regular entry")
        relative = PurePosixPath(path.relative_to(root).as_posix())
        name = str(relative)
        if relative.is_absolute() or ".." in relative.parts or not name:
            raise ValueError("payload contains an unsafe relative path")
        identity = name.casefold()
        if identity in folded:
            raise ValueError("payload contains a case-insensitive path collision")
        folded.add(identity)
        if path.suffix.casefold() in FORBIDDEN_SUFFIXES or path.name.casefold() == "chatserver.exe":
            raise ValueError("payload contains a forbidden build or server artifact")
        size = path.stat().st_size
        if size <= 0:
            raise ValueError("payload contains an empty file")
        result[name] = {"size": size, "sha256": digest(path)}

    if not EXECUTABLES.issubset(result):
        raise ValueError("payload is missing a required executable")
    if "sqldrivers/qsqlite.dll" not in result:
        raise ValueError("payload is missing the Qt SQLite driver")
    sodium = [name for name in result if "/" not in name and "sodium" in name.casefold()
              and name.casefold().endswith(".dll")]
    if len(sodium) != 1:
        raise ValueError("payload must contain exactly one root libsodium runtime")
    return result


def compare(baseline: Path, candidate: Path) -> tuple[dict, dict]:
    left = inventory(baseline)
    right = inventory(candidate)
    if set(left) != set(right):
        missing = sorted(set(left) - set(right))
        extra = sorted(set(right) - set(left))
        raise ValueError(f"payload inventories differ: missing={missing}, extra={extra}")
    for name in sorted(set(left) - EXECUTABLES):
        if left[name] != right[name]:
            raise ValueError(f"deployed runtime bytes differ: {name}")
    return left, right


def write_evidence(path: Path, evidence: dict) -> None:
    if not path.is_absolute() or path.exists() or path.is_symlink():
        raise ValueError("evidence path must be a new absolute file")
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = (json.dumps(evidence, ensure_ascii=False, sort_keys=True,
                          separators=(",", ":")) + "\n").encode("utf-8")
    descriptor, temporary = tempfile.mkstemp(prefix=".payload-parity-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not VERSION.fullmatch(args.version) or not REVISION.fullmatch(args.source_revision):
        raise SystemExit("version or source revision is invalid")
    baseline = args.baseline.resolve(strict=True)
    candidate = args.candidate.resolve(strict=True)
    output = args.output.resolve(strict=False)
    if baseline == candidate or baseline in output.parents or candidate in output.parents:
        raise SystemExit("payload roots and evidence path must be separate")
    try:
        left, right = compare(baseline, candidate)
        write_evidence(output, {
            "schemaVersion": 1,
            "version": args.version,
            "sourceRevision": args.source_revision,
            "baselineBuildSystem": "qmake",
            "candidateBuildSystem": "cmake",
            "runtimeBytesEquivalent": True,
            "executableByteDifferencesAllowed": sorted(EXECUTABLES),
            "baseline": left,
            "candidate": right,
        })
    except (OSError, ValueError) as error:
        raise SystemExit(str(error)) from error
    print(f"Windows deployed payload parity evidence written: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
