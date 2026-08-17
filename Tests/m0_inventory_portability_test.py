#!/usr/bin/env python3
"""Lock the M0 source inventory to platform-independent paths and line endings."""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path, PureWindowsPath


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import m0_inventory  # noqa: E402


def main() -> int:
    with tempfile.TemporaryDirectory() as directory:
        temporary = Path(directory)
        lf_source = temporary / "lf.cpp"
        crlf_source = temporary / "crlf.cpp"
        changed_source = temporary / "changed.cpp"
        lf_source.write_bytes(b"first line\nsecond line\n")
        crlf_source.write_bytes(b"first line\r\nsecond line\r\n")
        changed_source.write_bytes(b"first line\nchanged line\n")

        assert m0_inventory.sha256(lf_source) == m0_inventory.sha256(crlf_source)
        assert m0_inventory.sha256(lf_source) != m0_inventory.sha256(changed_source)

    windows_root = PureWindowsPath("D:/a/ChatRoom/ChatRoom")
    windows_source = windows_root / "Server" / "DatabaseManager.cpp"
    assert (
        m0_inventory.relative_source_path(windows_source, windows_root)
        == "Server/DatabaseManager.cpp"
    )

    print("M0 inventory portability policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
