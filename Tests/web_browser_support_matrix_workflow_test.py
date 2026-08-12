#!/usr/bin/env python3

from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class WebBrowserSupportMatrixWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = (
            ROOT / ".github/workflows/m4-web-browser-support-matrix.yml"
        ).read_text(encoding="utf-8")
        self.config = (ROOT / "WebClient/playwright.config.ts").read_text(encoding="utf-8")
        self.spec = (ROOT / "WebClient/e2e/browserCompatibility.spec.ts").read_text(
            encoding="utf-8")

    def test_uses_six_exact_protected_branded_browser_hosts(self) -> None:
        for target in (
            "chrome-current", "chrome-previous", "edge-current",
            "edge-previous", "firefox-current", "firefox-previous",
        ):
            self.assertIn(f"chatroom-web-{target}", self.workflow)
        for value in (
            "environment: web-browser-support", "self-hosted, Linux, X64",
            "CHATROOM_BRANDED_BROWSER_EXECUTABLE", "actions/download-artifact@v8",
        ):
            self.assertIn(value, self.workflow)

    def test_binds_exact_candidate_binary_and_runtime_results(self) -> None:
        for value in (
            "web_release_store import validate_release", "sha256sum",
            "npm ci", "npm run test:browser", "web_browser_host_evidence.py",
            "EXPECTED_BROWSER_VERSION", "EXPECTED_BROWSER_EXECUTABLE_SHA256",
            "retention-days: 30",
        ):
            self.assertIn(value, self.workflow)
        self.assertIn("launchOptions: { executablePath: brandedExecutable }", self.config)
        for value in (
            "browser.version()", "navigator.userAgent", "indexedDB.open",
            "artifactManifestSha256", "flag: \"wx\"",
        ):
            self.assertIn(value, self.spec)

    def test_has_no_install_latest_publication_or_production_mutation(self) -> None:
        combined = (self.workflow + self.config + self.spec).lower()
        for forbidden in (
            "playwright install", "apt-get", "dnf install", "release create",
            "upload release asset", "active-release.json", "web-production",
        ):
            self.assertNotIn(forbidden, combined)
        self.assertNotIn('"${{ inputs.chrome_current_version }}"', self.workflow)
        self.assertNotIn('"${{ inputs.chrome_current_sha256 }}"', self.workflow)


if __name__ == "__main__":
    unittest.main()
