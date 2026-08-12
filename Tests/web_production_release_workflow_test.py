#!/usr/bin/env python3

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class WebProductionReleaseWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source = (ROOT / ".github/workflows/m4-web-production-release.yml").read_text(
            encoding="utf-8")

    def test_separates_technical_preview_from_reviewed_production_mutation(self) -> None:
        for value in (
            "technical-readiness:", "production-promotion:",
            "environment: web-production", "web_preview_release.py select",
            "WEB_PREVIEW_ORIGIN", "WEB_PRODUCTION_ORIGIN",
            "needs: technical-readiness", "production-authorization.json",
            "Refresh exact technical evidence after production approval",
            "technical-promotion-reviewed.json",
        ):
            self.assertIn(value, self.source)
        self.assertLess(self.source.index("technical-readiness:"),
                        self.source.index("production-promotion:"))

    def test_revalidates_switches_observes_and_rolls_back_on_failure(self) -> None:
        for value in (
            "web_promotion_evidence.py record", "web_promotion_evidence.py verify",
            "web_release_authorization.py create", "web_release_authorization.py verify",
            "web_release_execution.py execute", "web_release_completion.py record",
            "web_release_rollback_execution.py execute",
            "web_release_rollback_completion.py record",
            "steps.execute.outcome == 'success'", "steps.complete.outcome != 'success'",
            "retention-days: 90",
        ):
            self.assertIn(value, self.source)

    def test_uses_fixed_runner_configuration_and_has_no_provider_or_build_authority(self) -> None:
        for value in (
            "vars.CHATROOM_WEB_STORE_ROOT", "vars.CHATROOM_WEB_PREVIEW_ORIGIN",
            "vars.CHATROOM_WEB_PRODUCTION_ORIGIN", "chatroom-web-release-store",
            "contents: read", "actions: read",
        ):
            self.assertIn(value, self.source)
        lowered = self.source.lower()
        for forbidden in (
            "npm run build", "npm ci", "cloudflare", "vercel", "aws ",
            "kubectl", "ssh ", "private key", "client_secret",
        ):
            self.assertNotIn(forbidden, lowered)
        run_blocks = re.findall(r"\n\s+run: \|\n((?:\s{10,}.*\n)+)", self.source)
        self.assertTrue(run_blocks)
        self.assertTrue(all("${{ inputs." not in block for block in run_blocks))


if __name__ == "__main__":
    unittest.main()
