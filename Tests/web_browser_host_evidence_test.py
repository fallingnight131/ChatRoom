#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import sys
import unittest
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError  # noqa: E402
from web_browser_host_evidence import verify_host_evidence  # noqa: E402
import web_release_store_test as release_fixture  # noqa: E402


class WebBrowserHostEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = release_fixture.WebReleaseStoreTest(
            methodName="test_stages_upgrades_and_rolls_back_by_atomic_pointer_without_rebuild")
        self.fixture.setUp()
        artifact = self.fixture.artifact("1.2.3", "a" * 40, "console.log('browser')\n")
        staged = __import__("web_release_store").stage_release(artifact, self.fixture.store)
        self.release = self.fixture.store / "releases" / staged["releaseId"]
        self.policy = ROOT / "packaging/web/browser-support-policy.json"
        self.path = self.fixture.root / "browser-evidence.json"
        self.now = datetime(2026, 8, 13, 12, 0, tzinfo=timezone.utc)
        manifest_sha = hashlib.sha256(
            (self.release / "web-artifact-manifest.json").read_bytes()
        ).hexdigest()
        self.value = {
            "schemaVersion": 2,
            "evidenceType": "web-browser-host-acceptance",
            "status": "candidate-smoke-observed",
            "product": "chat-room-web-client",
            "targetId": "chrome-current",
            "browserFamily": "chrome",
            "browserProduct": "Google Chrome",
            "supportPosition": "current",
            "browserVersion": "140.0.7339.80",
            "browserExecutableSha256": "b" * 64,
            "platform": "Windows 11 24H2",
            "architecture": "x86_64",
            "userAgent": "Mozilla/5.0 Chrome/140.0.7339.80 Safari/537.36",
            "releaseId": staged["releaseId"],
            "version": "1.2.3",
            "sourceRevision": "a" * 40,
            "artifactManifestSha256": manifest_sha,
            "checks": {
                "productionLoginSurface": True,
                "requiredWebCapabilities": True,
                "indexedDb": True,
                "serverEndpointIsolation": True,
                "responsiveLogin": True,
                "keyboardAccessibleLogin": True,
                "announcedValidationError": True,
                "noPageErrors": True,
            },
            "observedAt": "2026-08-13T12:00:00Z",
        }
        self.write(self.value)

    def tearDown(self) -> None:
        self.fixture.tearDown()

    def write(self, value) -> None:
        self.path.write_text(json.dumps(value), encoding="utf-8")

    def verify(self):
        return verify_host_evidence(
            self.path, self.policy, "chrome-current", self.release,
            "140.0.7339.80", "b" * 64, self.now)

    def test_accepts_exact_branded_browser_candidate_smoke(self) -> None:
        self.assertEqual(self.verify()["status"], "candidate-smoke-observed")

    def test_rejects_wrong_target_version_or_executable(self) -> None:
        with self.assertRaises(ManifestError):
            verify_host_evidence(
                self.path, self.policy, "edge-current", self.release,
                "140.0.7339.80", "b" * 64, self.now)
        for version, digest in (("141.0.0.0", "b" * 64), ("140.0.7339.80", "c" * 64)):
            with self.subTest(version=version, digest=digest[0]):
                with self.assertRaises(ManifestError):
                    verify_host_evidence(
                        self.path, self.policy, "chrome-current", self.release,
                        version, digest, self.now)

    def test_rejects_failed_check_release_drift_and_stale_record(self) -> None:
        for mutation in ("check", "release", "stale"):
            changed = deepcopy(self.value)
            if mutation == "check":
                changed["checks"]["indexedDb"] = False
            elif mutation == "release":
                changed["artifactManifestSha256"] = "d" * 64
            else:
                changed["observedAt"] = "2026-08-12T11:59:59Z"
            self.write(changed)
            with self.subTest(mutation=mutation), self.assertRaises(ManifestError):
                self.verify()

    def test_rejects_duplicate_keys_and_unbranded_user_agent(self) -> None:
        self.path.write_text('{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate"):
            self.verify()
        changed = deepcopy(self.value)
        changed["userAgent"] = "Mozilla/5.0 Safari/605.1.15"
        self.write(changed)
        with self.assertRaises(ManifestError):
            self.verify()


if __name__ == "__main__":
    unittest.main()
