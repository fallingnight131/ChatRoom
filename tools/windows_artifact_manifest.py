#!/usr/bin/env python3
"""Create a deterministic manifest for an unsigned Windows client payload."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Iterable

from artifact_manifest_common import (
    ManifestError,
    atomic_write,
    payload_files,
    read_version,
    sha256_file,
    validate_revision,
)

QT_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


def build_manifest(
    payload_root: Path,
    version_file: Path,
    source_revision: str,
    qt_version: str,
) -> tuple[dict[str, object], list[str]]:
    validate_revision(source_revision)
    if not QT_VERSION.fullmatch(qt_version):
        raise ManifestError("Qt version must use major.minor.patch")

    entries: list[dict[str, object]] = []
    checksum_lines: list[str] = []
    for path in payload_files(payload_root):
        relative = path.relative_to(payload_root).as_posix()
        digest, size = sha256_file(path)
        artifact_path = f"client/{relative}"
        entries.append({"path": artifact_path, "sha256": digest, "size": size})
        checksum_lines.append(f"{digest}  {artifact_path}")

    manifest: dict[str, object] = {
        "schemaVersion": 1,
        "product": "chat-room-windows-client",
        "version": read_version(version_file),
        "channel": "verification",
        "platform": "windows",
        "architecture": "x86_64",
        "toolchain": "msvc2022",
        "qtVersion": qt_version,
        "sourceRevision": source_revision,
        "signatureStatus": "unsigned-verification-only",
        "files": entries,
    }
    return manifest, checksum_lines


def write_manifest(output_dir: Path, manifest: dict[str, object], checksums: Iterable[str]) -> None:
    atomic_write(
        output_dir / "artifact-manifest.json",
        json.dumps(manifest, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
    )
    atomic_write(output_dir / "SHA256SUMS", "\n".join(checksums) + "\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--payload-root", type=Path, required=True)
    parser.add_argument("--version-file", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--qt-version", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        manifest, checksums = build_manifest(
            args.payload_root,
            args.version_file,
            args.source_revision,
            args.qt_version,
        )
        write_manifest(args.output_dir, manifest, checksums)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"windows artifact manifest failed: {error}") from None
    print(
        "windows artifact manifest: "
        f"version={manifest['version']} files={len(manifest['files'])} "
        "signature=unsigned-verification-only"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
