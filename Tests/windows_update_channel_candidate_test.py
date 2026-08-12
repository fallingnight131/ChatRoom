#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
from datetime import timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError, atomic_write, sha256_file  # noqa: E402
from windows_release_candidate_test import WindowsReleaseCandidateTest  # noqa: E402
from windows_update_channel_candidate import (  # noqa: E402
    assemble_candidate, validate_candidate,
)
from windows_update_manifest import build_manifest, canonical_bytes, sign_manifest  # noqa: E402


class WindowsUpdateChannelCandidateTest(WindowsReleaseCandidateTest):
    def setUp(self) -> None:
        super().setUp()
        self.update_manifest = self.root / "manifest.json"
        self.update_signature = self.root / "manifest.json.sig"
        self.private_key = self.root / "update-private.pem"
        self.public_key = self.root / "update-public.pem"
        self.update_candidate = self.root / "update-candidate"

    def prepare_update_inputs(self) -> None:
        if not self.candidate.exists():
            self.assemble()
        subprocess.run(
            ["openssl", "genpkey", "-algorithm", "Ed25519", "-out", str(self.private_key)],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(
            ["openssl", "pkey", "-in", str(self.private_key), "-pubout",
             "-out", str(self.public_key)],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        manifest = build_manifest(
            self.candidate / f"installer/ChatRoom-{self.version}-Setup.exe",
            version=self.version, channel="stable", manifest_sequence=42,
            signing_key_id="windows-update-2026-01",
            published_at="2026-08-12T12:00:00Z",
            expires_at="2026-08-19T12:00:00Z",
            minimum_updatable_version="1.0.0", source_revision=self.revision,
            rollout_percentage=10, rollout_seed="e" * 64,
            installer_url=(
                f"https://updates.example.test/stable/ChatRoom-{self.version}-Setup.exe"),
            authenticode_sha256_thumbprint=self.signer,
        )
        atomic_write(self.update_manifest, canonical_bytes(manifest).decode("utf-8"))
        sign_manifest(self.update_manifest, self.private_key, self.update_signature)

    def public_digest(self) -> str:
        return hashlib.sha256(self.public_key.read_bytes()).hexdigest()

    def assemble_update(self):
        self.prepare_update_inputs()
        return assemble_candidate(
            self.candidate, self.update_manifest, self.update_signature,
            self.public_key, self.update_candidate, self.version_file,
            self.revision, "stable", "6.11.1", self.signer,
            self.public_digest(), self.now,
        )

    def validate_update(self, root=None, now=None):
        return validate_candidate(
            root or self.update_candidate, self.version_file, self.revision,
            "stable", "6.11.1", self.signer, self.public_digest(),
            now or self.now,
        )

    def test_assembles_self_contained_signed_unpublished_candidate(self) -> None:
        assembled = self.assemble_update()
        verified = self.validate_update()
        self.assertEqual(assembled["status"],
                         "signed-update-channel-not-published-candidate")
        self.assertEqual(assembled["manifestSequence"], 42)
        self.assertEqual(verified["fileCount"], assembled["fileCount"])
        self.assertTrue((self.update_candidate / "update/manifest.json.sig").is_file())
        self.assertTrue((self.update_candidate / "windows/client/ChatClient.exe").is_file())
        self.assertFalse(any("private" in path.name.lower()
                             for path in self.update_candidate.rglob("*")))

    def test_archived_candidate_remains_verifiable_at_assembly_time(self) -> None:
        self.assemble_update()
        self.assertEqual(
            self.validate_update(now=self.now + timedelta(days=90))["manifestSequence"], 42)

    def test_rejects_tamper_extra_private_material_and_identity_change(self) -> None:
        self.assemble_update()
        changed = self.root / "changed-update"
        shutil.copytree(self.update_candidate, changed)
        (changed / "update/manifest.json.sig").write_bytes(b"x" * 64)
        with self.assertRaises(ManifestError):
            self.validate_update(changed)

        changed = self.root / "extra-private"
        shutil.copytree(self.update_candidate, changed)
        (changed / "private.pem").write_text("forbidden", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "undeclared"):
            self.validate_update(changed)

        changed = self.root / "outer-identity"
        shutil.copytree(self.update_candidate, changed)
        path = changed / "windows-update-channel-candidate.json"
        value = json.loads(path.read_text(encoding="utf-8"))
        value["version"] = "9.9.9"
        path.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "does not match"):
            self.validate_update(changed)

    def test_rejects_re_signed_manifest_for_different_installer_hash(self) -> None:
        self.prepare_update_inputs()
        manifest = json.loads(self.update_manifest.read_text(encoding="utf-8"))
        manifest["installer"]["sha256"] = "f" * 64
        self.update_manifest.unlink()
        self.update_signature.unlink()
        atomic_write(self.update_manifest, canonical_bytes(manifest).decode("utf-8"))
        sign_manifest(self.update_manifest, self.private_key, self.update_signature)
        with self.assertRaisesRegex(ManifestError, "does not match signed candidate"):
            assemble_candidate(
                self.candidate, self.update_manifest, self.update_signature,
                self.public_key, self.update_candidate, self.version_file,
                self.revision, "stable", "6.11.1", self.signer,
                self.public_digest(), self.now,
            )


if __name__ == "__main__":
    import unittest
    unittest.main()
