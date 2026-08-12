#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError, sha256_file  # noqa: E402
import windows_release_candidate_test as candidate_fixture  # noqa: E402
from windows_support_host_evidence import verify_host_evidence  # noqa: E402


class WindowsSupportHostEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.current = candidate_fixture.WindowsReleaseCandidateTest(
            methodName="test_atomically_assembles_and_revalidates_complete_candidate")
        self.previous = candidate_fixture.WindowsReleaseCandidateTest(
            methodName="test_atomically_assembles_and_revalidates_complete_candidate")
        self.previous.release_version = "1.2.2"
        self.previous.source_revision = "c" * 40
        self.current.setUp()
        self.previous.setUp()
        self.current.assemble()
        self.previous.assemble()
        self.policy = ROOT / "packaging/windows/support-matrix-policy.json"
        self.evidence_path = self.current.root / "support-host.json"
        self.evidence = self.build_evidence()
        self.write(self.evidence)

    def tearDown(self) -> None:
        self.previous.tearDown()
        self.current.tearDown()

    def build_evidence(self):
        current_sha, _ = sha256_file(
            self.current.candidate / "windows-release-candidate.json")
        previous_sha, _ = sha256_file(
            self.previous.candidate / "windows-release-candidate.json")
        return {
            "schemaVersion": 1,
            "evidenceType": "windows-support-host-acceptance",
            "status": "clean-install-upgrade-uninstall-observed",
            "product": "chat-room-windows-client",
            "targetId": "windows-11-24h2",
            "architecture": "x86_64",
            "osCaption": "Microsoft Windows 11 Enterprise",
            "osVersion": "10.0.26100",
            "osBuild": 26100,
            "osProductType": 1,
            "currentVersion": self.current.version,
            "currentSourceRevision": self.current.revision,
            "previousVersion": self.previous.version,
            "previousSourceRevision": self.previous.revision,
            "channel": "stable",
            "qtVersion": "6.11.1",
            "expectedSignerCertificateSha256": self.current.signer,
            "currentCandidateManifestSha256": current_sha,
            "previousCandidateManifestSha256": previous_sha,
            "checks": {
                "cleanHost": True,
                "previousInstalled": True,
                "previousLaunched": True,
                "upgradeSucceeded": True,
                "accountDataPreservedOnUpgrade": True,
                "currentLaunched": True,
                "runningClientUpgradeRejected": True,
                "downgradeRejected": True,
                "uninstallSucceeded": True,
                "accountDataPreservedOnUninstall": True,
                "programFilesRemoved": True,
                "registrationRemoved": True,
            },
            "observedAt": "2026-08-12T12:00:00Z",
        }

    def write(self, value):
        self.evidence_path.write_text(json.dumps(value), encoding="utf-8")

    def verify(self, target="windows-11-24h2"):
        return verify_host_evidence(
            self.evidence_path, self.policy, target,
            self.current.candidate, self.current.version_file,
            self.current.revision, self.previous.candidate,
            self.previous.version_file, self.previous.revision, "stable",
            "6.11.1", self.current.signer, self.current.now)

    def test_accepts_exact_closed_supported_host_transition(self) -> None:
        self.assertEqual(
            self.verify()["status"],
            "clean-install-upgrade-uninstall-observed")

    def test_rejects_server_host_wrong_build_and_unknown_target(self) -> None:
        for key, value in (("osProductType", 3), ("osBuild", 26200)):
            with self.subTest(key=key):
                changed = deepcopy(self.evidence)
                changed[key] = value
                self.write(changed)
                with self.assertRaises(ManifestError):
                    self.verify()
        self.write(self.evidence)
        with self.assertRaisesRegex(ManifestError, "missing or duplicated"):
            self.verify("windows-12")

    def test_rejects_any_missing_acceptance_check_or_candidate_drift(self) -> None:
        changed = deepcopy(self.evidence)
        changed["checks"]["currentLaunched"] = False
        self.write(changed)
        with self.assertRaises(ManifestError):
            self.verify()
        changed = deepcopy(self.evidence)
        changed["currentCandidateManifestSha256"] = "d" * 64
        self.write(changed)
        with self.assertRaises(ManifestError):
            self.verify()

    def test_rejects_duplicate_shape_and_stale_evidence(self) -> None:
        self.evidence_path.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate"):
            self.verify()
        changed = deepcopy(self.evidence)
        changed["observedAt"] = "2026-08-11T11:59:59Z"
        self.write(changed)
        with self.assertRaisesRegex(ManifestError, "stale"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
