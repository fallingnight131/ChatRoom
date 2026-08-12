#!/usr/bin/env python3

from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class WindowsSupportMatrixWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = (ROOT / ".github/workflows/m4-windows-support-matrix.yml").read_text(
            encoding="utf-8")
        self.script = (ROOT / "tools/verify_windows_support_host.ps1").read_text(
            encoding="utf-8")

    def test_uses_exact_client_hosts_and_two_prior_protected_artifacts(self) -> None:
        for value in (
            "chatroom-windows-10-22h2-clean",
            "chatroom-windows-11-23h2-clean",
            "chatroom-windows-11-24h2-clean",
            "current_run_id", "previous_run_id", "actions/download-artifact@v8",
            "windows-client-support-matrix",
        ):
            self.assertIn(value, self.workflow)
        self.assertIn("$env:CURRENT_REVISION", self.workflow)
        self.assertNotIn('"${{ inputs.current_source_revision }}"', self.workflow)
        self.assertNotIn('"${{ inputs.expected_signer_sha256 }}"', self.workflow)
        self.assertNotIn("windows-2025", self.workflow)
        self.assertNotIn("windows-2022", self.workflow)

    def test_closes_candidates_then_runs_and_verifies_full_host_acceptance(self) -> None:
        for value in (
            "windows_release_candidate.py verify",
            "verify_windows_support_host.ps1",
            "windows_support_host_evidence.py",
            "previous_source_revision", "expected_signer_sha256",
            "retention-days: 30",
        ):
            self.assertIn(value, self.workflow)
        for value in (
            "ProductType", "Require-Signed", "TimeStamperCertificate",
            "Previous client", "Current client", "ExitCode -ne 4",
            "downgradeRejected", "accountDataPreservedOnUpgrade",
            "accountDataPreservedOnUninstall", "registrationRemoved",
        ):
            self.assertIn(value, self.script)

    def test_has_no_signing_or_publication_authority(self) -> None:
        combined = (self.workflow + self.script).lower()
        for forbidden in (
            "signtool", "certificate_sha1", "private key", "pkcs11",
            "release create", "upload release asset", "azure/login",
        ):
            self.assertNotIn(forbidden, combined)


if __name__ == "__main__":
    unittest.main()
