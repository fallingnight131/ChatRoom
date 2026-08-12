#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
from copy import deepcopy
from datetime import datetime, timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError, atomic_write  # noqa: E402
from windows_update_channel_candidate_test import (  # noqa: E402
    WindowsUpdateChannelCandidateTest,
)
from windows_update_release_authorization import (  # noqa: E402
    create_authorization, verify_authorization, write_once,
)
from windows_update_manifest import canonical_bytes  # noqa: E402


class WindowsUpdateReleaseAuthorizationTest(WindowsUpdateChannelCandidateTest):
    def setUp(self) -> None:
        super().setUp()
        self.authorization = self.root / "update-authorization.json"
        self.current_manifest = self.root / "current-manifest.json"

    def prepare_current_manifest(self) -> None:
        if not self.update_candidate.exists():
            self.assemble_update()
        if not self.current_manifest.exists():
            value = json.loads(
                (self.update_candidate / "update/manifest.json").read_text(encoding="utf-8"))
            value["manifestSequence"] = 41
            atomic_write(self.current_manifest, canonical_bytes(value).decode("utf-8"))

    def create(self, now=None, lifetime=900):
        self.prepare_current_manifest()
        return create_authorization(
            self.update_candidate, self.current_manifest, self.version_file,
            self.revision, "stable", "6.11.1", self.signer,
            self.public_digest(), now or self.now, lifetime,
        )

    def verify_authorization(self, now=None):
        self.prepare_current_manifest()
        return verify_authorization(
            self.authorization, self.update_candidate, self.current_manifest,
            self.version_file, self.revision, "stable", "6.11.1", self.signer,
            self.public_digest(), now or self.now,
        )

    def test_creates_closed_short_lived_write_once_authorization(self) -> None:
        value = self.create()
        write_once(self.authorization, value)
        verified = self.verify_authorization()
        self.assertEqual(verified["environment"], "windows-update-production")
        self.assertEqual(verified["manifestSequence"], 42)
        self.assertEqual(verified["expectedCurrentManifestSequence"], 41)
        self.assertEqual(verified["schemaVersion"], 2)
        self.assertEqual(verified["targetRolloutPercentage"], 10)
        self.assertEqual(verified["expectedCurrentRolloutPercentage"], 10)
        self.assertNotIn("credential", json.dumps(verified).lower())
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.authorization, value)

    def test_rejects_non_advancing_sequence_expiry_and_stale_candidate(self) -> None:
        self.prepare_current_manifest()
        current = json.loads(self.current_manifest.read_text(encoding="utf-8"))
        current["manifestSequence"] = 42
        atomic_write(self.current_manifest, canonical_bytes(current).decode("utf-8"))
        with self.assertRaisesRegex(ManifestError, "does not advance"):
            self.create()
        current["manifestSequence"] = 41
        atomic_write(self.current_manifest, canonical_bytes(current).decode("utf-8"))
        write_once(self.authorization, self.create())
        with self.assertRaisesRegex(ManifestError, "expired"):
            self.verify_authorization(self.now + timedelta(minutes=15))
        with self.assertRaisesRegex(ManifestError, "candidate is stale"):
            self.create(now=self.now + timedelta(hours=25))

    def test_rejects_rollout_change_through_general_promotion_authorization(self) -> None:
        self.prepare_current_manifest()
        current = json.loads(self.current_manifest.read_text(encoding="utf-8"))
        current["rollout"]["percentage"] = 5
        atomic_write(self.current_manifest, canonical_bytes(current).decode("utf-8"))
        with self.assertRaisesRegex(ManifestError, "health-bound authorization"):
            self.create()

    def test_rejects_authorization_or_candidate_mutation(self) -> None:
        value = self.create()
        write_once(self.authorization, value)
        changed = deepcopy(value)
        changed["expectedCurrentManifestSha256"] = "2" * 64
        self.authorization.write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "does not match"):
            self.verify_authorization()

        write_once(self.root / "clean-authorization.json", value)
        self.authorization = self.root / "clean-authorization.json"
        current = json.loads(self.current_manifest.read_text(encoding="utf-8"))
        current["manifestSequence"] = 40
        atomic_write(self.current_manifest, canonical_bytes(current).decode("utf-8"))
        with self.assertRaisesRegex(ManifestError, "does not match"):
            self.verify_authorization()
        current["manifestSequence"] = 41
        atomic_write(self.current_manifest, canonical_bytes(current).decode("utf-8"))

        setup = self.update_candidate / f"windows/installer/ChatRoom-{self.version}-Setup.exe"
        setup.write_bytes(b"changed")
        with self.assertRaises(ManifestError):
            self.verify_authorization()

    def test_rejects_duplicate_shape_and_unsafe_clock_or_lifetime(self) -> None:
        self.authorization.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            self.verify_authorization()
        with self.assertRaisesRegex(ManifestError, "60 to 900"):
            self.create(lifetime=59)
        with self.assertRaisesRegex(ManifestError, "exact UTC"):
            self.create(now=datetime(2026, 8, 12, 12, 0, 0))

    def test_contains_no_network_or_channel_mutation_adapter(self) -> None:
        source = (ROOT / "tools/windows_update_release_authorization.py").read_text(
            encoding="utf-8").lower()
        for marker in (
            "import requests", "import subprocess", "urllib.request", "boto3",
            "cloudflare", "vercel", "ssh", "kubectl", "invoke-webrequest",
        ):
            self.assertNotIn(marker, source)


if __name__ == "__main__":
    import unittest
    unittest.main()
