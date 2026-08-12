#!/usr/bin/env python3

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "packaging" / "windows" / "ChatRoom.nsi"


class WindowsInstallerPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = SCRIPT.read_text(encoding="utf-8")

    def test_requires_traceable_build_inputs_and_unsigned_name(self) -> None:
        for name in ["VERSION", "SOURCE_REVISION", "PAYLOAD_DIR", "OUTPUT_DIR", "ICON_FILE"]:
            self.assertRegex(self.source, rf"!ifndef {name}\b")
        self.assertIn("ChatRoom-${VERSION}-unsigned-verification-Setup.exe", self.source)
        self.assertNotRegex(self.source, r"(?im)^\s*!finalize\b")
        self.assertNotRegex(self.source, r"(?im)^\s*!uninstfinalize\b")

        workflow = (ROOT / ".github" / "workflows" / "m0-product-builds.yml").read_text(encoding="utf-8")
        self.assertIn("& $makensis /WX /NOCONFIG /V2", workflow)

    def test_is_per_user_and_registers_standard_uninstall_metadata(self) -> None:
        self.assertIn("RequestExecutionLevel user", self.source)
        self.assertIn('InstallDir "$LOCALAPPDATA\\Programs\\ChatRoom"', self.source)
        self.assertNotIn("RequestExecutionLevel admin", self.source)
        self.assertNotIn("WriteRegStr HKLM", self.source)
        for value in ["DisplayName", "DisplayVersion", "InstallLocation", "UninstallString", "QuietUninstallString"]:
            self.assertIn(f'"{value}"', self.source)

    def test_installs_only_payload_and_preserves_account_local_data(self) -> None:
        self.assertIn('File /r "${PAYLOAD_DIR}\\*"', self.source)
        self.assertIn('WriteUninstaller "$INSTDIR\\Uninstall.exe"', self.source)
        self.assertIn('IfFileExists "$INSTDIR\\${PRODUCT_EXE}" 0 unsafe_uninstall', self.source)
        self.assertNotRegex(self.source, r"(?i)(APPDATA|LOCALAPPDATA).*RMDir|RMDir.*(APPDATA|LOCALAPPDATA)")
        self.assertNotIn("ChatServer.exe", self.source)

    def test_emits_required_integrity_and_windows_metadata(self) -> None:
        for directive in [
            "Unicode true",
            "CRCCheck force",
            "ManifestDPIAware true",
            "ManifestSupportedOS Win10",
            'VIProductVersion "${VERSION}.0"',
        ]:
            self.assertIn(directive, self.source)


if __name__ == "__main__":
    unittest.main()
