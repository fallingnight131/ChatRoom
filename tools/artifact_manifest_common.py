"""Fail-closed helpers shared by release artifact manifest tools."""

from __future__ import annotations

import hashlib
import os
import re
import tempfile
from pathlib import Path


SEMVER = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
REVISION = re.compile(r"^[0-9a-f]{40}$")


class ManifestError(ValueError):
    """The payload or release identity is unsafe or incomplete."""


def read_version(version_file: Path) -> str:
    raw = version_file.read_text(encoding="utf-8")
    version = raw.rstrip("\r\n")
    if not version or raw not in {version + "\n", version + "\r\n"} or not SEMVER.fullmatch(version):
        raise ManifestError("VERSION must contain one canonical numeric SemVer line")
    return version


def validate_revision(source_revision: str) -> None:
    if not REVISION.fullmatch(source_revision):
        raise ManifestError("source revision must be a lowercase 40-character Git SHA")


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


def atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", newline="\n", dir=path.parent, delete=False
    ) as stream:
        stream.write(content)
        temporary = Path(stream.name)
    os.replace(temporary, path)
