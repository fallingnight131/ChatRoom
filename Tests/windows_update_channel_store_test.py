#!/usr/bin/env python3

from __future__ import annotations

import os
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError  # noqa: E402
from windows_update_channel_candidate_test import (  # noqa: E402
    WindowsUpdateChannelCandidateTest,
)
from windows_update_channel_store import (  # noqa: E402
    stage_release, validate_release, validate_release_from_candidate,
)


class WindowsUpdateChannelStoreTest(WindowsUpdateChannelCandidateTest):
    def setUp(self) -> None:
        super().setUp()
        self.store = self.root / "channel-store"

    def stage(self):
        if not self.update_candidate.exists():
            self.assemble_update()
        return stage_release(
            self.update_candidate, self.store, self.version_file, self.revision,
            "stable", "6.11.1", self.signer, self.public_digest(), self.now,
        )

    def test_stages_complete_candidate_by_manifest_digest_idempotently(self) -> None:
        first = self.stage()
        self.assertEqual(first["stageStatus"], "staged")
        second = self.stage()
        self.assertEqual(second["stageStatus"], "already-present")
        release = self.store / "releases" / first["releaseId"]
        verified = validate_release(
            release, self.version_file, self.revision, "stable", "6.11.1",
            self.signer, self.public_digest(), self.now,
        )
        self.assertEqual(verified["releaseId"], first["releaseId"])
        self.assertEqual(
            validate_release_from_candidate(release, self.now)["releaseId"],
            first["releaseId"],
        )
        self.assertTrue((release / "windows/client/ChatClient.exe").is_file())

    def test_rejects_changed_staged_bytes_and_unsafe_release_boundary(self) -> None:
        identity = self.stage()
        release = self.store / "releases" / identity["releaseId"]
        (release / "update/manifest.json.sig").write_bytes(b"x" * 64)
        with self.assertRaises(ManifestError):
            self.stage()

    @__import__("unittest").skipIf(os.name == "nt", "Windows symlink needs privileges")
    def test_rejects_symlinked_releases_boundary(self) -> None:
        self.assemble_update()
        outside = self.root / "outside"
        outside.mkdir()
        self.store.mkdir()
        (self.store / "releases").symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ManifestError, "boundary is unsafe"):
            self.stage()

    def test_contains_no_activation_or_network_adapter(self) -> None:
        source = (ROOT / "tools/windows_update_channel_store.py").read_text(
            encoding="utf-8").lower()
        for marker in (
            "active-release", "active-channel", "import requests", "urllib.request",
            "boto3", "cloudflare", "vercel", "invoke-webrequest",
        ):
            self.assertNotIn(marker, source)


if __name__ == "__main__":
    import unittest
    unittest.main()
