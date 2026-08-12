#!/usr/bin/env python3

from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError, atomic_write  # noqa: E402
from windows_update_manifest import build_manifest, canonical_bytes  # noqa: E402
from windows_update_rollout_health import (  # noqa: E402
    canonical_metrics_bytes, evaluate, verify, write_once,
)


class WindowsUpdateRolloutHealthTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.candidate = self.root / "candidate"
        (self.candidate / "update").mkdir(parents=True)
        self.revision = "a" * 40
        self.signer = "b" * 64
        installer = self.root / "ChatRoom-1.2.3-Setup.exe"
        installer.write_bytes(b"setup")
        manifest = build_manifest(
            installer, version="1.2.3", channel="stable", manifest_sequence=42,
            signing_key_id="windows-update-2026-01",
            published_at="2026-08-12T10:00:00Z",
            expires_at="2026-08-19T10:00:00Z",
            minimum_updatable_version="1.0.0", source_revision=self.revision,
            rollout_percentage=1, rollout_seed="c" * 64,
            installer_url=(
                "https://updates.example.test/stable/ChatRoom-1.2.3-Setup.exe"),
            authenticode_sha256_thumbprint=self.signer,
        )
        self.manifest_path = self.candidate / "update/manifest.json"
        atomic_write(self.manifest_path, canonical_bytes(manifest).decode("utf-8"))
        import hashlib
        self.release_id = hashlib.sha256(self.manifest_path.read_bytes()).hexdigest()
        self.completion = self.root / "completion.json"
        self.completion.write_text(json.dumps({
            "schemaVersion": 1,
            "evidenceType": "windows-update-production-promotion-completion",
            "status": "production-update-promotion-observed",
            "channel": "stable",
            "releaseId": self.release_id,
            "rollbackReleaseId": "d" * 64,
            "version": "1.2.3",
            "sourceRevision": self.revision,
            "manifestSequence": 42,
            "manifestUrl": "https://updates.example.test/stable/manifest.json",
            "executionSha256": "e" * 64,
            "observationSha256": "f" * 64,
            "executedAt": "2026-08-12T10:00:00Z",
            "observedAt": "2026-08-12T10:00:30Z",
            "completedAt": "2026-08-12T10:01:00Z",
            "maximumCompletionSeconds": 600,
        }), encoding="utf-8")
        self.metrics = self.root / "metrics.json"
        self.policy = ROOT / "packaging/windows/rollout-health-policy.json"
        self.now = datetime(2026, 8, 12, 12, 2, 0, tzinfo=timezone.utc)
        self.output = self.root / "decision.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def metrics_value(self, **changes):
        value = {
            "schemaVersion": 1,
            "evidenceType": "windows-update-aggregate-health",
            "channel": "stable",
            "releaseId": self.release_id,
            "version": "1.2.3",
            "sourceRevision": self.revision,
            "manifestSequence": 42,
            "rolloutPercentage": 1,
            "windowStartedAt": "2026-08-12T10:01:00Z",
            "windowEndedAt": "2026-08-12T12:01:00Z",
            "updateChecks": 200,
            "eligibleDevices": 120,
            "downloadStarted": 110,
            "installSucceeded": 99,
            "installFailed": 1,
            "crashAffectedDevices": 0,
        }
        value.update(changes)
        return value

    def write_metrics(self, **changes) -> None:
        self.metrics.write_bytes(canonical_metrics_bytes(self.metrics_value(**changes)))

    def test_marks_only_complete_low_error_window_expand_eligible(self) -> None:
        self.write_metrics()
        value = evaluate(
            self.completion, self.candidate, self.metrics, self.policy, self.now)
        self.assertEqual(value["decision"], "expand-eligible")
        self.assertEqual(value["nextRolloutPercentage"], 5)
        self.assertEqual(value["installFailureBasisPoints"], 100)
        write_once(self.output, value)
        self.assertEqual(
            verify(self.output, self.completion, self.candidate, self.metrics, self.policy),
            value)
        self.write_metrics(installSucceeded=98, installFailed=2)
        with self.assertRaisesRegex(ManifestError, "does not match"):
            verify(self.output, self.completion, self.candidate, self.metrics, self.policy)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.output, value)

    def test_holds_incomplete_or_insufficient_evidence(self) -> None:
        for changes in (
            {"windowStartedAt": "2026-08-12T11:01:01Z"},
            {"installSucceeded": 20, "installFailed": 0,
             "downloadStarted": 30, "eligibleDevices": 40, "updateChecks": 50},
        ):
            with self.subTest(changes=changes):
                self.write_metrics(**changes)
                value = evaluate(
                    self.completion, self.candidate, self.metrics, self.policy, self.now)
                self.assertEqual(value["decision"], "hold")
                self.assertIsNone(value["nextRolloutPercentage"])

    def test_recommends_halt_only_after_emergency_count_and_rate(self) -> None:
        self.write_metrics(
            updateChecks=20, eligibleDevices=10, downloadStarted=6,
            installSucceeded=3, installFailed=3, crashAffectedDevices=0)
        value = evaluate(
            self.completion, self.candidate, self.metrics, self.policy, self.now)
        self.assertEqual(value["decision"], "halt-recommended")
        self.assertEqual(value["installFailureBasisPoints"], 5000)

    def test_rejects_identity_counter_stale_and_policy_mutations(self) -> None:
        mutations = (
            ({"releaseId": "0" * 64}, "identity"),
            ({"downloadStarted": 99, "installSucceeded": 100}, "inconsistent"),
            ({"windowEndedAt": "2026-08-12T11:00:00Z"}, "stale"),
            ({"rolloutPercentage": 2}, "identity|approved steps"),
            ({"rolloutPercentage": True}, "approved steps"),
        )
        for changes, message in mutations:
            with self.subTest(changes=changes):
                self.write_metrics(**changes)
                with self.assertRaisesRegex(ManifestError, message):
                    evaluate(
                        self.completion, self.candidate, self.metrics,
                        self.policy, self.now)
        self.write_metrics()
        changed_policy = self.root / "policy.json"
        shutil.copyfile(self.policy, changed_policy)
        value = json.loads(changed_policy.read_text(encoding="utf-8"))
        value["channels"]["stable"]["rolloutSteps"] = [1, 100, 5]
        changed_policy.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "policy value"):
            evaluate(
                self.completion, self.candidate, self.metrics,
                changed_policy, self.now)
        self.metrics.write_text(json.dumps(self.metrics_value()), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "not canonical"):
            evaluate(
                self.completion, self.candidate, self.metrics,
                self.policy, self.now)


if __name__ == "__main__":
    unittest.main()
