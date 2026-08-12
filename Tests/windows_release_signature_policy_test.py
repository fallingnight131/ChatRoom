#!/usr/bin/env python3

from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "verify_windows_release_signatures.ps1"
WORKFLOW = ROOT / ".github" / "workflows" / "m0-product-builds.yml"


class WindowsReleaseSignaturePolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = SCRIPT.read_text(encoding="utf-8")
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_requires_exact_release_identity_without_private_key_inputs(self) -> None:
        for parameter in [
            "ClientPath", "LauncherPath", "UninstallerPath", "InstallerPath", "Version",
            "SourceRevision", "ExpectedSignerSha256", "EvidencePath",
        ]:
            self.assertRegex(self.source, rf"\${parameter}\b")
        self.assertIn('"ChatClient.exe"', self.source)
        self.assertIn('"ChatRoomUpdateLauncher.exe"', self.source)
        self.assertIn('"ChatRoom-$Version-Uninstall.exe"', self.source)
        self.assertIn('"ChatRoom-$Version-Setup.exe"', self.source)
        self.assertIn("schemaVersion = 2", self.source)
        self.assertNotRegex(self.source, r"(?i)(private.?key|pfx.?password|certificate.?password)")

    def test_requires_valid_sha256_bound_timestamped_authenticode(self) -> None:
        for policy in [
            "Get-AuthenticodeSignature",
            "SignatureStatus]::Valid",
            "SignerCertificate",
            "TimeStamperCertificate",
            "HashAlgorithmName]::SHA256",
            "ExpectedSignerSha256",
            'signatureStatus = "valid-timestamped-authenticode"',
        ]:
            self.assertIn(policy, self.source)
        self.assertGreaterEqual(self.source.count("Inspect-ReleaseSignature"), 4)

    def test_rejects_links_and_writes_evidence_only_after_all_checks(self) -> None:
        self.assertIn("FileAttributes]::ReparsePoint", self.source)
        self.assertIn("already exists", self.source)
        self.assertIn("Move-Item -LiteralPath $temporaryPath", self.source)
        evidence_index = self.source.index("$evidence = [ordered]@{")
        for call in [
            'Inspect-ReleaseSignature $client "client"',
            'Inspect-ReleaseSignature $launcher "update-launcher"',
            'Inspect-ReleaseSignature $uninstaller "uninstaller"',
            'Inspect-ReleaseSignature $installer "installer"',
        ]:
            self.assertLess(self.source.index(call), evidence_index)
        self.assertLess(self.source.index("$artifacts = @("), evidence_index)
        self.assertGreater(self.source.index("Move-Item -LiteralPath $temporaryPath"), evidence_index)

    def test_native_ci_proves_unsigned_artifacts_are_rejected(self) -> None:
        self.assertIn("verify_windows_release_signatures.ps1", self.workflow)
        self.assertIn("Unsigned verification artifacts unexpectedly passed release signature policy", self.workflow)
        self.assertIn("Unsigned release-signature rejection created evidence", self.workflow)


if __name__ == "__main__":
    unittest.main()
