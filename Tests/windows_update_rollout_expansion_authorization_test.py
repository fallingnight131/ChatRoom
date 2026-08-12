#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError, atomic_write  # noqa: E402
from windows_update_channel_candidate import assemble_candidate  # noqa: E402
from windows_update_manifest import canonical_bytes, sign_manifest  # noqa: E402
from windows_update_release_completion import write_once as write_completion  # noqa: E402
import windows_update_release_completion_test as completion_fixture  # noqa: E402
from windows_update_rollout_expansion_authorization import (  # noqa: E402
    create_authorization, verify_authorization, write_once,
)
from windows_update_rollout_health import (  # noqa: E402
    canonical_metrics_bytes, evaluate as evaluate_health,
    write_once as write_health,
)


class WindowsUpdateRolloutExpansionAuthorizationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = completion_fixture.WindowsUpdateReleaseCompletionTest(
            methodName="test_binds_execution_and_post_switch_observation_once")
        self.fixture.setUp()
        self.fixture.prepare_completion()
        write_completion(self.fixture.completion, self.fixture.build())
        self.root = self.fixture.root
        self.now = self.fixture.now + timedelta(minutes=5)
        self.policy = self.root / "expansion-policy.json"
        self.policy.write_text(json.dumps({
            "schemaVersion": 1,
            "channels": {
                "stable": {
                    "rolloutSteps": [10, 25, 100],
                    "minObservationSeconds": 60,
                    "minInstallOutcomes": 2,
                    "maxInstallFailureBasisPoints": 100,
                    "maxCrashBasisPoints": 50,
                    "emergencyInstallFailureCount": 3,
                    "emergencyCrashCount": 2,
                },
                "beta": {
                    "rolloutSteps": [10, 100],
                    "minObservationSeconds": 60,
                    "minInstallOutcomes": 2,
                    "maxInstallFailureBasisPoints": 100,
                    "maxCrashBasisPoints": 50,
                    "emergencyInstallFailureCount": 3,
                    "emergencyCrashCount": 2,
                },
            },
        }), encoding="utf-8")
        current_manifest = self.fixture.target_release / "update/manifest.json"
        release_id = hashlib.sha256(current_manifest.read_bytes()).hexdigest()
        self.metrics = self.root / "rollout-metrics.json"
        metrics = {
            "schemaVersion": 1,
            "evidenceType": "windows-update-aggregate-health",
            "channel": "stable",
            "releaseId": release_id,
            "version": self.fixture.version,
            "sourceRevision": self.fixture.revision,
            "manifestSequence": 42,
            "rolloutPercentage": 10,
            "windowStartedAt": "2026-08-12T12:02:00Z",
            "windowEndedAt": "2026-08-12T12:04:00Z",
            "updateChecks": 10,
            "eligibleDevices": 5,
            "downloadStarted": 3,
            "installSucceeded": 2,
            "installFailed": 0,
            "crashAffectedDevices": 0,
        }
        self.metrics.write_bytes(canonical_metrics_bytes(metrics))
        self.health = self.root / "rollout-health.json"
        write_health(self.health, evaluate_health(
            self.fixture.completion, self.fixture.target_release,
            self.metrics, self.policy, self.now - timedelta(minutes=1)))
        self.metrics_private = self.root / "metrics-private.pem"
        self.metrics_public = self.root / "metrics-public.pem"
        self.metrics_signature = self.root / "metrics.json.sig"
        subprocess.run(
            ["openssl", "genpkey", "-algorithm", "Ed25519",
             "-out", str(self.metrics_private)], check=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(
            ["openssl", "pkey", "-in", str(self.metrics_private), "-pubout",
             "-out", str(self.metrics_public)], check=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(
            ["openssl", "pkeyutl", "-sign", "-rawin",
             "-inkey", str(self.metrics_private), "-in", str(self.metrics),
             "-out", str(self.metrics_signature)], check=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.metrics_public_sha = hashlib.sha256(
            self.metrics_public.read_bytes()).hexdigest()
        self.target = self.build_target(25, 43, self.root / "expanded-candidate")
        self.authorization = self.root / "expansion-authorization.json"

    def tearDown(self) -> None:
        self.fixture.tearDown()

    def build_target(self, percentage: int, sequence: int, output: Path,
                     seed: str | None = None) -> Path:
        value = json.loads((
            self.fixture.target_release / "update/manifest.json").read_text(
                encoding="utf-8"))
        value["manifestSequence"] = sequence
        value["rollout"]["percentage"] = percentage
        if seed is not None:
            value["rollout"]["seed"] = seed
        suffix = "default" if seed is None else seed[:8]
        manifest = self.root / f"target-{percentage}-{sequence}-{suffix}.json"
        signature = self.root / f"target-{percentage}-{sequence}-{suffix}.json.sig"
        atomic_write(manifest, canonical_bytes(value).decode("utf-8"))
        sign_manifest(manifest, self.fixture.private_key, signature)
        assemble_candidate(
            self.fixture.candidate, manifest, signature, self.fixture.public_key,
            output, self.fixture.version_file, self.fixture.revision, "stable",
            "6.11.1", self.fixture.signer, self.fixture.public_digest(), self.now)
        return output

    def values(self, target=None):
        return (
            self.fixture.completion, self.fixture.execution,
            self.fixture.authorization, self.fixture.target_release,
            self.fixture.rollback_release, self.fixture.current_manifest,
            self.fixture.observation, self.health, self.metrics,
            self.metrics_signature, self.metrics_public, "windows-metrics-2026-01",
            self.metrics_public_sha, self.policy, target or self.target,
            self.fixture.version_file, self.fixture.revision, "stable", "6.11.1",
            self.fixture.signer, self.fixture.public_digest(),
        )

    def test_authorizes_only_attested_next_percentage_once(self) -> None:
        value = create_authorization(*self.values(), self.now)
        self.assertEqual(value["status"], "rollout-expansion-approved-not-executed")
        self.assertEqual(value["currentRolloutPercentage"], 10)
        self.assertEqual(value["targetRolloutPercentage"], 25)
        write_once(self.authorization, value)
        self.assertEqual(verify_authorization(
            self.authorization, *self.values(), now_utc=self.now), value)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.authorization, value)

    def test_rejects_skipped_step_seed_change_and_metrics_tamper(self) -> None:
        skipped = self.build_target(100, 43, self.root / "skipped-candidate")
        with self.assertRaisesRegex(ManifestError, "approved next step"):
            create_authorization(*self.values(skipped), self.now)
        reseeded = self.build_target(
            25, 43, self.root / "reseeded-candidate", seed="9" * 64)
        with self.assertRaisesRegex(ManifestError, "approved next step"):
            create_authorization(*self.values(reseeded), self.now)
        self.metrics_signature.write_bytes(b"x" * 64)
        with self.assertRaisesRegex(ManifestError, "attestation"):
            create_authorization(*self.values(), self.now)

    def test_rejects_stale_health_expired_authorization_and_target_drift(self) -> None:
        with self.assertRaisesRegex(ManifestError, "health decision is stale"):
            create_authorization(*self.values(), self.now + timedelta(minutes=5))
        value = create_authorization(*self.values(), self.now, 60)
        write_once(self.authorization, value)
        with self.assertRaisesRegex(ManifestError, "expired"):
            verify_authorization(
                self.authorization, *self.values(),
                now_utc=self.now + timedelta(seconds=60))
        target_manifest = self.target / "update/manifest.json"
        target_manifest.write_bytes(target_manifest.read_bytes() + b" ")
        with self.assertRaises(ManifestError):
            create_authorization(*self.values(), self.now)

    def test_contains_no_private_key_network_or_channel_mutation_adapter(self) -> None:
        source = (ROOT / "tools/windows_update_rollout_expansion_authorization.py").read_text(
            encoding="utf-8").lower()
        for marker in (
            "private-key", "private_key", "import requests", "urllib.request",
            "boto3", "cloudflare", "vercel", "kubectl", "invoke-webrequest",
        ):
            self.assertNotIn(marker, source)


if __name__ == "__main__":
    unittest.main()
