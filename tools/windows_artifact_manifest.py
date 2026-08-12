#!/usr/bin/env python3
"""Create a deterministic manifest for an unsigned Windows client payload."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tempfile
from pathlib import Path
from typing import Iterable


SEMVER = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
REVISION = re.compile(r"^[0-9a-f]{40}$")
QT_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


class ManifestError(ValueError):
    """The payload or release identity is unsafe or incomplete."""


def read_version(version_file: Path) -> str:
    raw = version_file.read_text(encoding="utf-8")
    version = raw.rstrip("\r\n")
    if not version or raw not in {version + "\n", version + "\r\n"} or not SEMVER.fullmatch(version):
        raise ManifestError("VERSION must contain one canonical SemVer line")
    return version


def payload_files(root: Path) -> list[Path]:
    if not root.is_dir() or root.is_symlink():
        raise ManifestError("payload root must be a real directory")
    files: list[Path] = []
    for current, directories, names in os.walk(root, followlinks=False):
        current_path = Path(current)
        for directory in directories:
            if (current_path / directory).is_symlink():
                raise ManifestError("payload must not contain symbolic links")
        for name in names:
            candidate = current_path / name
            if candidate.is_symlink():
                raise ManifestError("payload must not contain symbolic links")
            if not candidate.is_file():
                raise ManifestError("payload must contain regular files only")
            files.append(candidate)
    if not files:
        raise ManifestError("payload must not be empty")
    return sorted(files, key=lambda path: path.relative_to(root).as_posix())


def sha256_file(path: Path) -> tuple[str, int]:
    before = path.stat()
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ManifestError("payload changed while it was being hashed")
    return digest.hexdigest(), after.st_size


def build_manifest(
    payload_root: Path,
    version_file: Path,
    source_revision: str,
    qt_version: str,
) -> tuple[dict[str, object], list[str]]:
    if not REVISION.fullmatch(source_revision):
        raise ManifestError("source revision must be a lowercase 40-character Git SHA")
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


def atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", newline="\n", dir=path.parent, delete=False
    ) as stream:
        stream.write(content)
        temporary = Path(stream.name)
    os.replace(temporary, path)


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
