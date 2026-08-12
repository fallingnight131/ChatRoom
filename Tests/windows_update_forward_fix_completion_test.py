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
import windows_update_forward_fix_execution_test as execution_fixture  # noqa: E402
from windows_update_forward_fix_completion import (  # noqa: E402
    complete, verify_completion,
)
from windows_update_forward_fix_execution import execute  # noqa: E402
from windows_update_incident_state import inspect_open_incident  # noqa: E402


class WindowsUpdateForwardFixCompletionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.base = execution_fixture.WindowsUpdateForwardFixExecutionTest(
            methodName="test_consumes_once_and_switches_exact_forward_fix")
        self.base.setUp()
        execute(
            self.base.base.forward_authorization, self.base.values,
            self.base.base.fixture.store, self.base.evidence, self.base.now)
        self.observed_at = self.base.now + timedelta(minutes=1)
        self.completed_at = self.base.now + timedelta(minutes=2)
        self.observation = self.base.base.root / "forward-fix-observation.json"
        self.completion = self.base.base.root / "forward-fix-completion.json"
        self.write_observation()

    def tearDown(self) -> None:
        self.base.tearDown()

    def write_observation(self, **changes) -> None:
        root = self.base.target_release
        manifest_path = root / "update/manifest.json"
        signature_path = root / "update/manifest.json.sig"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        installer = (
            root / "windows/installer"
            / f"ChatRoom-{manifest['version']}-Setup.exe")
        manifest_sha, _ = sha256_file(manifest_path)
        signature_sha, _ = sha256_file(signature_path)
        installer_sha, installer_size = sha256_file(installer)
        directory = manifest["installer"]["url"].rsplit("/", 1)[0]
        value = {
            "schemaVersion": 1,
            "evidenceType": "windows-update-https-observation",
            "status": "healthy",
            "channel": manifest["channel"],
            "version": manifest["version"],
            "sourceRevision": manifest["sourceRevision"],
            "manifestSequence": manifest["manifestSequence"],
            "signingKeyId": manifest["signingKeyId"],
            "manifestUrl": f"{directory}/manifest.json",
            "signatureUrl": f"{directory}/manifest.json.sig",
            "installerUrl": manifest["installer"]["url"],
            "manifestSha256": manifest_sha,
            "signatureSha256": signature_sha,
            "installerSha256": installer_sha,
            "installerSize": installer_size,
            "authenticodeSignerSha256": self.base.target.signer,
            "observedAt": self.observed_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
            **changes,
        }
        self.observation.write_text(json.dumps(value), encoding="utf-8")

    def inputs(self):
        return (
            self.base.evidence, self.base.base.forward_authorization,
            self.base.values, self.observation, self.base.base.fixture.store,
        )

    def test_observation_completes_forward_fix_and_resolves_incident(self) -> None:
        value = complete(self.completion, *self.inputs(), self.completed_at)
        self.assertEqual(value["status"], "production-forward-fix-observed")
        self.assertIsNone(inspect_open_incident(
            self.base.base.fixture.store, self.completed_at))
        self.assertEqual(verify_completion(self.completion, *self.inputs()), value)
        resolved = (
            self.base.base.fixture.store / ".resolved-rollout-incidents"
            / f"{value['incidentId']}.json")
        self.assertTrue(resolved.is_file())

    def test_rejects_wrong_or_late_observation_without_closing_incident(self) -> None:
        self.write_observation(manifestSequence=99)
        with self.assertRaises(ManifestError):
            complete(self.completion, *self.inputs(), self.completed_at)
        self.assertIsNotNone(inspect_open_incident(
            self.base.base.fixture.store, self.completed_at))
        self.write_observation(
            observedAt=(self.base.now + timedelta(minutes=11)).strftime(
                "%Y-%m-%dT%H:%M:%SZ"))
        with self.assertRaisesRegex(ManifestError, "outside"):
            complete(
                self.completion, *self.inputs(),
                self.base.now + timedelta(minutes=11))
        self.assertIsNotNone(inspect_open_incident(
            self.base.base.fixture.store,
            self.base.now + timedelta(minutes=11)))

    def test_rejects_occupied_completion_and_keeps_incident_open(self) -> None:
        self.completion.write_text("occupied", encoding="utf-8")
        with self.assertRaises(ManifestError):
            complete(self.completion, *self.inputs(), self.completed_at)
        self.assertIsNotNone(inspect_open_incident(
            self.base.base.fixture.store, self.completed_at))

    def test_rejects_completion_or_resolution_mutation(self) -> None:
        value = complete(self.completion, *self.inputs(), self.completed_at)
        changed = dict(value)
        changed["manifestSequence"] += 1
        self.completion.write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "differs"):
            verify_completion(self.completion, *self.inputs())


if __name__ == "__main__":
    unittest.main()
