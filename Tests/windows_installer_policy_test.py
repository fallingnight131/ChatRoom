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
        self.assertIn("!else ifdef RELEASE_BUILD", self.source)
        self.assertIn("ChatRoom-${VERSION}-Setup.exe", self.source)
        self.assertNotRegex(self.source, r"(?im)^\s*!finalize\b")
        self.assertIn("export_windows_uninstaller.py", self.source)
        self.assertNotRegex(
            self.source,
            r"(?im)^\s*!uninstfinalize[^\n]*(signtool|certificate|timestamp|password|private.?key)",
        )

        workflow = (ROOT / ".github" / "workflows" / "m0-product-builds.yml").read_text(encoding="utf-8")
        self.assertIn("& $makensis /WX /NOCONFIG /V2", workflow)
        for evidence in [
            '$priorVersion = "0.9.0"',
            "$oldProgramSentinel",
            "Silent upgrade failed",
            "Upgrade retained a stale program file",
            "$installRoot.__chatroom_stage",
            "$installRoot.__chatroom_backup",
            "Upgrade deleted account-local client data",
            "Older installer was allowed to downgrade",
            "Rejected downgrade changed the current installation",
            "Running-client upgrade returned",
            "Rejected running-client upgrade changed the process, installation, or account data",
            'Get-ChildItem "$env:SODIUM_ROOT/bin" -Filter "*sodium*.dll"',
            "Installed client is missing the update-verifier libsodium runtime",
            "Installed update launcher is missing",
            "Update launcher did not complete its parent-process handshake",
            "Update launcher did not atomically reject and clean the unsigned fixture",
        ]:
            self.assertIn(evidence, workflow)

    def test_is_per_user_and_registers_standard_uninstall_metadata(self) -> None:
        self.assertIn("RequestExecutionLevel user", self.source)
        self.assertIn('InstallDir "$LOCALAPPDATA\\Programs\\ChatRoom"', self.source)
        self.assertNotIn("RequestExecutionLevel admin", self.source)
        self.assertNotIn("WriteRegStr HKLM", self.source)
        for value in ["DisplayName", "DisplayVersion", "InstallLocation", "UninstallString", "QuietUninstallString"]:
            self.assertIn(f'"{value}"', self.source)

    def test_installs_only_payload_and_preserves_account_local_data(self) -> None:
        self.assertIn('File /r "${PAYLOAD_DIR}\\*"', self.source)
        self.assertIn('WriteUninstaller "$StageDir\\Uninstall.exe"', self.source)
        self.assertIn('File /oname=Uninstall.exe "${OUTPUT_DIR}\\ChatRoom-${VERSION}-Uninstall.exe"', self.source)
        self.assertIn('IfFileExists "$INSTDIR\\${PRODUCT_EXE}" 0 unsafe_uninstall', self.source)
        self.assertNotRegex(self.source, r"(?i)(APPDATA|LOCALAPPDATA).*RMDir|RMDir.*(APPDATA|LOCALAPPDATA)")
        self.assertNotIn("ChatServer.exe", self.source)

    def test_stages_and_swaps_owned_program_directories_for_upgrade(self) -> None:
        for value in [
            '"chat-room-windows-client-v1"',
            '".chat-room-install.ini"',
            'StrCpy $StageDir "$INSTDIR.__chatroom_stage"',
            'StrCpy $BackupDir "$INSTDIR.__chatroom_backup"',
            'Rename "$INSTDIR" "$BackupDir"',
            'Rename "$StageDir" "$INSTDIR"',
            'Rename "$BackupDir" "$INSTDIR"',
            'IfFileExists "$StageDir\\sqldrivers\\qsqlite.dll" 0 stage_invalid',
            'IfFileExists "$StageDir\\${PRODUCT_UPDATE_LAUNCHER}" 0 stage_invalid',
            'SetOutPath "$TEMP"',
            '${VersionCompare} "${VERSION}" "$1" $2',
            'StrCmp $2 "2" downgrade_install',
        ]:
            self.assertIn(value, self.source)
        self.assertRegex(self.source, r'ReadINIStr \$0 "\$INSTDIR\\\$\{PRODUCT_INSTALL_MARKER\}"')
        self.assertIn('StrCmp $0 "${PRODUCT_INSTALL_ID}" 0 unsafe_install', self.source)
        self.assertIn('StrCmp $0 "${PRODUCT_INSTALL_ID}" 0 unsafe_uninstall', self.source)
        self.assertLess(self.source.index('File /r "${PAYLOAD_DIR}\\*"'), self.source.index('Rename "$StageDir" "$INSTDIR"'))
        self.assertLess(self.source.index('SetOutPath "$TEMP"'), self.source.index('Rename "$StageDir" "$INSTDIR"'))

    def test_rejects_a_running_client_before_mutation(self) -> None:
        self.assertIn('!define PRODUCT_RUNNING_MUTEX "Local\\ChatRoom.WindowsClient.Running.v1"', self.source)
        self.assertIn("OpenMutexW", self.source)
        self.assertIn("IfSilent running_client_abort", self.source)
        self.assertIn("SetErrorLevel 4", self.source)
        self.assertLess(self.source.index("Function .onInit"), self.source.index('CreateDirectory "$StageDir"'))
        client_source = (ROOT / "Client" / "WindowsClientInstanceGuard.cpp").read_text(encoding="utf-8")
        self.assertIn("Local\\\\ChatRoom.WindowsClient.Running.v1", client_source)

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
