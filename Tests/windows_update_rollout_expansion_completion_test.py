#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import unittest
from datetime import timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError, sha256_file  # noqa: E402
import windows_update_rollout_expansion_execution_test as execution_fixture  # noqa: E402
from windows_update_rollout_expansion_execution import execute  # noqa: E402
from windows_update_rollout_expansion_completion import (  # noqa: E402
    build_completion, verify_completion, write_once,
)


class WindowsUpdateRolloutExpansionCompletionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.base = execution_fixture.WindowsUpdateRolloutExpansionExecutionTest(
            methodName="test_consumes_once_and_switches_exact_staged_percentage")
        self.base.setUp()
        execute(
            self.base.base.authorization, self.base.values,
            self.base.base.fixture.store, self.base.evidence, self.base.base.now)
        self.observation = self.base.base.root / "expansion-observation.json"
        self.completion = self.base.base.root / "expansion-completion.json"
        self.write_observation(self.base.base.now + timedelta(minutes=1))

    def tearDown(self) -> None:
        self.base.tearDown()

    def write_observation(self, observed_at) -> None:
        target = self.base.target_release
        manifest_path = target / "update/manifest.json"
        signature_path = target / "update/manifest.json.sig"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        installer = target / f"windows/installer/ChatRoom-{self.base.base.fixture.version}-Setup.exe"
        manifest_sha, _ = sha256_file(manifest_path)
        signature_sha, _ = sha256_file(signature_path)
        installer_sha, installer_size = sha256_file(installer)
        directory = manifest["installer"]["url"].rsplit("/", 1)[0]
        self.observation.write_text(json.dumps({
            "schemaVersion": 1,
            "evidenceType": "windows-update-https-observation",
            "status": "healthy",
            "channel": "stable",
            "version": self.base.base.fixture.version,
            "sourceRevision": self.base.base.fixture.revision,
            "manifestSequence": 43,
            "signingKeyId": manifest["signingKeyId"],
            "manifestUrl": f"{directory}/manifest.json",
            "signatureUrl": f"{directory}/manifest.json.sig",
            "installerUrl": manifest["installer"]["url"],
            "manifestSha256": manifest_sha,
            "signatureSha256": signature_sha,
            "installerSha256": installer_sha,
            "installerSize": installer_size,
            "authenticodeSignerSha256": self.base.base.fixture.signer,
            "observedAt": observed_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
        }), encoding="utf-8")

    def build(self, now=None, maximum=600):
        return build_completion(
            self.base.evidence, self.base.base.authorization, self.base.values,
            self.observation,
            now or self.base.base.now + timedelta(minutes=2), maximum)

    def test_binds_expansion_execution_to_public_observation_once(self) -> None:
        value = self.build()
        self.assertEqual(value["status"], "production-rollout-expansion-observed")
        self.assertEqual(value["currentRolloutPercentage"], 10)
        self.assertEqual(value["targetRolloutPercentage"], 25)
        write_once(self.completion, value)
        self.assertEqual(verify_completion(
            self.completion, self.base.evidence, self.base.base.authorization,
            self.base.values, self.observation), value)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.completion, value)

    def test_rejects_pre_switch_late_and_identity_mismatch(self) -> None:
        self.write_observation(self.base.base.now - timedelta(seconds=1))
        with self.assertRaisesRegex(ManifestError, "outside completion window|from the future"):
            self.build()
        self.write_observation(self.base.base.now + timedelta(minutes=11))
        with self.assertRaisesRegex(ManifestError, "outside completion window"):
            self.build(now=self.base.base.now + timedelta(minutes=11))
        self.write_observation(self.base.base.now + timedelta(minutes=1))
        value = json.loads(self.observation.read_text(encoding="utf-8"))
        value["manifestSequence"] = 44
        self.observation.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "identity differs|does not match candidate"):
            self.build()

    def test_rejects_completion_mutation_duplicate_and_bad_window(self) -> None:
        value = self.build()
        self.completion.write_text(
            json.dumps({**value, "targetRolloutPercentage": 50}), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "differs"):
            verify_completion(
                self.completion, self.base.evidence, self.base.base.authorization,
                self.base.values, self.observation)
        self.completion.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            verify_completion(
                self.completion, self.base.evidence, self.base.base.authorization,
                self.base.values, self.observation)
        with self.assertRaisesRegex(ManifestError, "60 to 900"):
            self.build(maximum=59)


if __name__ == "__main__":
    unittest.main()
