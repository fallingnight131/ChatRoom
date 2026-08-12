#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "export_windows_uninstaller.py"
SPEC = importlib.util.spec_from_file_location("export_windows_uninstaller", MODULE_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class WindowsUninstallerExportTest(unittest.TestCase):
    def test_exports_exact_pe_bytes_once(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "generated.exe"
            destination = root / "ChatRoom-1.2.3-Uninstall.exe"
            payload = b"MZ" + b"closed-uninstaller"
            source.write_bytes(payload)

            digest = MODULE.export_uninstaller(source, destination)

            self.assertEqual(destination.read_bytes(), payload)
            self.assertEqual(len(digest), 64)
            with self.assertRaisesRegex(ValueError, "must not overwrite"):
                MODULE.export_uninstaller(source, destination)

    def test_rejects_unsafe_source_and_destination(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            non_pe = root / "not-pe.exe"
            non_pe.write_bytes(b"not PE")
            with self.assertRaisesRegex(ValueError, "not a PE"):
                MODULE.export_uninstaller(non_pe, root / "ChatRoom-1.2.3-Uninstall.exe")

            source = root / "generated.exe"
            source.write_bytes(b"MZsafe")
            with self.assertRaisesRegex(ValueError, "name is invalid"):
                MODULE.export_uninstaller(source, root / "Uninstall.exe")

            link = root / "generated-link.exe"
            link.symlink_to(source)
            with self.assertRaisesRegex(ValueError, "regular file"):
                MODULE.export_uninstaller(link, root / "ChatRoom-1.2.3-Uninstall.exe")


if __name__ == "__main__":
    unittest.main()
