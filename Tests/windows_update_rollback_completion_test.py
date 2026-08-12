#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
from datetime import timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError, sha256_file  # noqa: E402
from windows_update_release_rollback_test import (  # noqa: E402
    WindowsUpdateReleaseRollbackTest,
)
from windows_update_rollback_completion import (  # noqa: E402
    build_completion, verify_completion, write_once,
)


class WindowsUpdateRollbackCompletionTest(WindowsUpdateReleaseRollbackTest):
    def setUp(self) -> None:
        super().setUp()
        self.restored_observation = self.root / "restored-observation.json"
        self.rollback_completion = self.root / "rollback-completion.json"

    def prepare_restored_observation(self) -> None:
        if not self.rollback_evidence.exists():
            self.run_rollback()
        if self.restored_observation.exists():
            return
        manifest_path = self.rollback_release / "update/manifest.json"
        signature_path = self.rollback_release / "update/manifest.json.sig"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        installer_path = (
            self.rollback_release
            / f"windows/installer/ChatRoom-{self.version}-Setup.exe")
        manifest_sha, _ = sha256_file(manifest_path)
        signature_sha, _ = sha256_file(signature_path)
        installer_sha, installer_size = sha256_file(installer_path)
        directory = manifest["installer"]["url"].rsplit("/", 1)[0]
        value = {
            "schemaVersion": 1,
            "evidenceType": "windows-update-https-observation",
            "status": "healthy",
            "channel": "stable",
            "version": self.version,
            "sourceRevision": self.revision,
            "manifestSequence": 41,
            "signingKeyId": manifest["signingKeyId"],
            "manifestUrl": f"{directory}/manifest.json",
            "signatureUrl": f"{directory}/manifest.json.sig",
            "installerUrl": manifest["installer"]["url"],
            "manifestSha256": manifest_sha,
            "signatureSha256": signature_sha,
            "installerSha256": installer_sha,
            "installerSize": installer_size,
            "authenticodeSignerSha256": self.signer,
            "observedAt": (self.now + timedelta(minutes=4)).strftime(
                "%Y-%m-%dT%H:%M:%SZ"),
        }
        self.restored_observation.write_text(json.dumps(value), encoding="utf-8")

    def inputs(self):
        return (
            self.rollback_evidence, self.completion, self.execution,
            self.authorization, self.target_release, self.rollback_release,
            self.current_manifest, self.version_file, self.revision, "stable",
            "6.11.1", self.signer, self.public_digest(), self.observation,
            self.restored_observation,
        )

    def build_rollback_completion(self, now=None, maximum=600):
        self.prepare_restored_observation()
        return build_completion(
            *self.inputs(), now or self.now + timedelta(minutes=5), maximum)

    def test_binds_restored_https_observation_to_rollout_halt_once(self) -> None:
        value = self.build_rollback_completion()
        write_once(self.rollback_completion, value)
        self.assertEqual(value["status"], "production-update-rollout-halt-observed")
        self.assertEqual(
            verify_completion(self.rollback_completion, *self.inputs()), value)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.rollback_completion, value)

    def test_rejects_late_or_identity_changed_restored_observation(self) -> None:
        self.prepare_restored_observation()
        original = json.loads(self.restored_observation.read_text(encoding="utf-8"))
        changed = dict(original)
        changed["observedAt"] = "2026-08-12T12:14:00Z"
        self.restored_observation.write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaises(ManifestError):
            self.build_rollback_completion(now=self.now + timedelta(minutes=14))
        changed = dict(original)
        changed["manifestSequence"] = 42
        self.restored_observation.write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaises(ManifestError):
            self.build_rollback_completion()

    def test_rejects_mutated_duplicate_and_invalid_completion(self) -> None:
        value = self.build_rollback_completion()
        self.rollback_completion.write_text(
            json.dumps({**value, "restoredReleaseId": value["failedReleaseId"]}),
            encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "does not match"):
            verify_completion(self.rollback_completion, *self.inputs())
        self.rollback_completion.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            verify_completion(self.rollback_completion, *self.inputs())
        with self.assertRaisesRegex(ManifestError, "60 to 900"):
            self.build_rollback_completion(maximum=59)


if __name__ == "__main__":
    import unittest
    unittest.main()
