#!/usr/bin/env python3

from __future__ import annotations

from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import tempfile
import unittest


import sys
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from artifact_manifest_common import ManifestError  # noqa: E402
from windows_protected_release_intent import create, verify  # noqa: E402


class WindowsProtectedReleaseIntentTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.version_file = self.root / "VERSION"
        self.version_file.write_text("1.2.3\n", encoding="utf-8")
        self.revision = "a" * 40
        self.signer_sha1 = "b" * 40
        self.signer_sha256 = "c" * 64
        self.now = datetime(2026, 8, 12, 12, 0, 0, tzinfo=timezone.utc)
        self.path = self.root / "protected-signing-intent.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def value(self):
        return create(
            self.version_file, self.revision, "stable", "123456789",
            self.signer_sha1, self.signer_sha256,
            "https://timestamp.example.test/rfc3161", self.now)

    def write(self, value=None) -> None:
        self.path.write_text(json.dumps(value or self.value()), encoding="utf-8")

    def test_creates_and_verifies_closed_approved_identity(self) -> None:
        value = self.value()
        self.write(value)
        verified = verify(
            self.path, self.version_file, self.revision, "stable",
            self.signer_sha256, self.now)
        self.assertEqual(verified, value)
        self.assertEqual(verified["environment"], "windows-production-signing")
        self.assertEqual(
            verified["unsignedArtifactName"],
            f"windows-stable-1.2.3-unsigned-product-trust-{self.revision}")
        self.assertNotIn("password", json.dumps(verified).lower())
        self.assertNotIn("private", json.dumps(verified).lower())

    def test_rejects_unsafe_creation_inputs(self) -> None:
        bad = (
            {"run_id": "0"},
            {"signer_sha1": "B" * 40},
            {"signer_sha256": "c" * 63},
            {"timestamp": "http://timestamp.example.test/rfc3161"},
            {"timestamp": "https://user@timestamp.example.test/rfc3161"},
            {"timestamp": "https://timestamp.example.test/rfc3161?token=x"},
        )
        defaults = {
            "run_id": "123", "signer_sha1": self.signer_sha1,
            "signer_sha256": self.signer_sha256,
            "timestamp": "https://timestamp.example.test/rfc3161",
        }
        for mutation in bad:
            values = {**defaults, **mutation}
            with self.subTest(mutation=mutation), self.assertRaises(ManifestError):
                create(
                    self.version_file, self.revision, "stable", values["run_id"],
                    values["signer_sha1"], values["signer_sha256"],
                    values["timestamp"], self.now)

    def test_rejects_identity_and_open_schema_mutations(self) -> None:
        for key, value in (
            ("version", "1.2.4"), ("sourceRevision", "d" * 40),
            ("channel", "beta"), ("buildSystem", "qmake"),
            ("environment", "unprotected"),
            ("expectedSignerCertificateSha256", "e" * 64),
            ("unsignedArtifactName", "other"), ("unknown", True),
        ):
            intent = self.value()
            intent[key] = value
            self.write(intent)
            with self.subTest(key=key), self.assertRaises(ManifestError):
                verify(
                    self.path, self.version_file, self.revision, "stable",
                    self.signer_sha256, self.now)

    def test_rejects_stale_future_and_malformed_times(self) -> None:
        for recorded in (
            (self.now - timedelta(hours=2, seconds=1)).strftime("%Y-%m-%dT%H:%M:%SZ"),
            (self.now + timedelta(minutes=5, seconds=1)).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "2026-08-12T12:00:00+00:00",
            "invalid",
        ):
            intent = self.value()
            intent["recordedAt"] = recorded
            self.write(intent)
            with self.subTest(recorded=recorded), self.assertRaises(ManifestError):
                verify(
                    self.path, self.version_file, self.revision, "stable",
                    self.signer_sha256, self.now)


if __name__ == "__main__":
    unittest.main()
