#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
from copy import deepcopy
from datetime import timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError, sha256_file  # noqa: E402
from windows_update_release_completion import (  # noqa: E402
    build_completion, verify_completion, write_once,
)
from windows_update_release_execution_test import (  # noqa: E402
    WindowsUpdateReleaseExecutionTest,
)


class WindowsUpdateReleaseCompletionTest(WindowsUpdateReleaseExecutionTest):
    def setUp(self) -> None:
        super().setUp()
        self.observation = self.root / "post-switch-observation.json"
        self.completion = self.root / "completion.json"

    def prepare_completion(self) -> None:
        if not self.execution.exists():
            self.run_execution()
        if self.observation.exists():
            return
        manifest_path = self.target_release / "update/manifest.json"
        signature_path = self.target_release / "update/manifest.json.sig"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        installer_path = (
            self.target_release
            / f"windows/installer/ChatRoom-{self.version}-Setup.exe")
        manifest_sha, _ = sha256_file(manifest_path)
        signature_sha, _ = sha256_file(signature_path)
        installer_sha, installer_size = sha256_file(installer_path)
        installer_url = manifest["installer"]["url"]
        directory = installer_url.rsplit("/", 1)[0]
        value = {
            "schemaVersion": 1,
            "evidenceType": "windows-update-https-observation",
            "status": "healthy",
            "channel": "stable",
            "version": self.version,
            "sourceRevision": self.revision,
            "manifestSequence": 42,
            "signingKeyId": manifest["signingKeyId"],
            "manifestUrl": f"{directory}/manifest.json",
            "signatureUrl": f"{directory}/manifest.json.sig",
            "installerUrl": installer_url,
            "manifestSha256": manifest_sha,
            "signatureSha256": signature_sha,
            "installerSha256": installer_sha,
            "installerSize": installer_size,
            "authenticodeSignerSha256": self.signer,
            "observedAt": (self.now + timedelta(minutes=1)).strftime(
                "%Y-%m-%dT%H:%M:%SZ"),
        }
        self.observation.write_text(json.dumps(value), encoding="utf-8")

    def build(self, now=None, maximum=600):
        self.prepare_completion()
        return build_completion(
            self.execution, self.authorization, self.target_release,
            self.rollback_release, self.current_manifest, self.version_file,
            self.revision, "stable", "6.11.1", self.signer,
            self.public_digest(), self.observation,
            now or self.now + timedelta(minutes=2), maximum,
        )

    def verify_file(self):
        return verify_completion(
            self.completion, self.execution, self.authorization,
            self.target_release, self.rollback_release, self.current_manifest,
            self.version_file, self.revision, "stable", "6.11.1", self.signer,
            self.public_digest(), self.observation,
        )

    def test_binds_execution_and_post_switch_observation_once(self) -> None:
        value = self.build()
        write_once(self.completion, value)
        self.assertEqual(value["status"], "production-update-promotion-observed")
        self.assertEqual(self.verify_file(), value)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.completion, value)

    def test_rejects_pre_switch_late_and_expired_completion(self) -> None:
        self.prepare_completion()
        original = json.loads(self.observation.read_text(encoding="utf-8"))
        for observed_at, now in (
            ("2026-08-12T11:59:59Z", self.now),
            ("2026-08-12T12:11:00Z", self.now + timedelta(minutes=11)),
        ):
            changed = deepcopy(original)
            changed["observedAt"] = observed_at
            self.observation.write_text(json.dumps(changed), encoding="utf-8")
            with self.assertRaises(ManifestError):
                self.build(now=now)
        self.observation.write_text(json.dumps(original), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "outside completion window"):
            self.build(now=self.now + timedelta(minutes=11))

    def test_rejects_observation_and_completion_mutation(self) -> None:
        value = self.build()
        write_once(self.completion, value)
        observation = json.loads(self.observation.read_text(encoding="utf-8"))
        observation["channel"] = "beta"
        self.observation.write_text(json.dumps(observation), encoding="utf-8")
        with self.assertRaises(ManifestError):
            self.verify_file()

    def test_rejects_unknown_duplicate_and_invalid_window(self) -> None:
        value = self.build()
        self.completion.write_text(json.dumps({**value, "unknown": True}), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "unsupported shape"):
            self.verify_file()
        self.completion.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            self.verify_file()
        with self.assertRaisesRegex(ManifestError, "60 to 900"):
            self.build(maximum=59)


if __name__ == "__main__":
    import unittest
    unittest.main()
