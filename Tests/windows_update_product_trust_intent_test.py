#!/usr/bin/env python3

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError  # noqa: E402
from windows_update_product_trust_intent import (  # noqa: E402
    create_intent, verify_intent, write_once,
)


class WindowsUpdateProductTrustIntentTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.version = self.root / "VERSION"
        self.version.write_text("1.2.3\n", encoding="utf-8")
        self.revision = "a" * 40
        self.now = datetime(2026, 8, 12, 12, 0, tzinfo=timezone.utc)
        self.primary = self.key("primary")
        self.secondary = self.key("secondary")
        self.intent = self.root / "trust-intent.json"
        self.url = "https://updates.example.test/windows/stable/manifest.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def key(self, name: str) -> Path:
        private = self.root / f"{name}-private.pem"
        public = self.root / f"{name}-public.pem"
        subprocess.run(
            ["openssl", "genpkey", "-algorithm", "Ed25519", "-out", str(private)],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(
            ["openssl", "pkey", "-in", str(private), "-pubout", "-out", str(public)],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return public

    def create(self, now=None, lifetime=7200, secondary=False):
        return create_intent(
            self.version, self.revision, "stable", self.url,
            "windows-update-2026-01", self.primary, now or self.now, lifetime,
            "windows-update-2027-01" if secondary else None,
            self.secondary if secondary else None,
        )

    def verify(self, now=None, secondary=False):
        return verify_intent(
            self.intent, self.version, self.revision, "stable", self.url,
            "windows-update-2026-01", self.primary, now or self.now,
            "windows-update-2027-01" if secondary else None,
            self.secondary if secondary else None,
        )

    def test_binds_exact_public_trust_without_private_material(self) -> None:
        value = self.create(secondary=True)
        write_once(self.intent, value)
        self.assertEqual(self.verify(secondary=True), value)
        self.assertEqual(len(value["primaryKey"]["publicKeyHex"]), 64)
        rendered = json.dumps(value).lower()
        for marker in ("private", "password", "secret", "pin"):
            self.assertNotIn(marker, rendered)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.intent, value)

    def test_rejects_expired_changed_and_duplicate_intent(self) -> None:
        write_once(self.intent, self.create())
        with self.assertRaisesRegex(ManifestError, "expired"):
            self.verify(self.now + timedelta(hours=2))
        value = json.loads(self.intent.read_text(encoding="utf-8"))
        value["channel"] = "beta"
        self.intent.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaises(ManifestError):
            self.verify()
        self.intent.write_text('{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            self.verify()

    def test_rejects_bad_url_key_pair_and_lifetime(self) -> None:
        with self.assertRaisesRegex(ManifestError, "manifest URL"):
            create_intent(
                self.version, self.revision, "stable",
                "https://updates.example.test/windows/beta/manifest.json",
                "windows-update-2026-01", self.primary, self.now)
        with self.assertRaisesRegex(ManifestError, "incomplete"):
            create_intent(
                self.version, self.revision, "stable", self.url,
                "windows-update-2026-01", self.primary, self.now,
                secondary_key_id="windows-update-2027-01")
        with self.assertRaisesRegex(ManifestError, "300 to 7200"):
            self.create(lifetime=299)

    def test_rejects_non_ed25519_public_key(self) -> None:
        rsa_private = self.root / "rsa-private.pem"
        rsa_public = self.root / "rsa-public.pem"
        subprocess.run(
            ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt",
             "rsa_keygen_bits:2048", "-out", str(rsa_private)],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(
            ["openssl", "pkey", "-in", str(rsa_private), "-pubout", "-out", str(rsa_public)],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        with self.assertRaisesRegex(ManifestError, "not Ed25519"):
            create_intent(
                self.version, self.revision, "stable", self.url,
                "windows-update-2026-01", rsa_public, self.now)


if __name__ == "__main__":
    unittest.main()
