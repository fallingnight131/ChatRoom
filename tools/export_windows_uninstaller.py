#!/usr/bin/env python3
"""Copy the NSIS-generated uninstaller into a closed external-signing subject."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re


NAME_PATTERN = re.compile(
    r"^ChatRoom-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-Uninstall\.exe$"
)


def export_uninstaller(source: Path, destination: Path) -> str:
    if not source.is_file() or source.is_symlink():
        raise ValueError("NSIS uninstaller source must be a regular file")
    if not NAME_PATTERN.fullmatch(destination.name):
        raise ValueError("NSIS uninstaller export name is invalid")
    if not destination.parent.is_dir() or destination.parent.is_symlink():
        raise ValueError("NSIS uninstaller export directory must already exist")
    if destination.exists() or destination.is_symlink():
        raise ValueError("NSIS uninstaller export must not overwrite an existing path")

    payload = source.read_bytes()
    if len(payload) < 2 or payload[:2] != b"MZ":
        raise ValueError("NSIS uninstaller source is not a PE executable")
    digest = hashlib.sha256(payload).hexdigest()
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        destination.unlink(missing_ok=True)
        raise
    if hashlib.sha256(destination.read_bytes()).hexdigest() != digest:
        destination.unlink(missing_ok=True)
        raise ValueError("NSIS uninstaller export changed bytes")
    return digest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    try:
        digest = export_uninstaller(args.source, args.destination)
    except (OSError, ValueError) as error:
        raise SystemExit(f"Windows uninstaller export failed: {error}") from None
    print(f"Windows uninstaller exported for external signing: sha256={digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
