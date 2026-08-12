#!/usr/bin/env python3

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from artifact_manifest_common import ManifestError, atomic_write  # noqa: E402
from windows_update_manifest import (  # noqa: E402
    build_manifest,
    canonical_bytes,
    read_canonical_manifest,
    sign_manifest,
    validate_manifest,
    verify_manifest_signature,
)


class WindowsUpdateManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.installer = self.root / "ChatRoom-1.2.3-Setup.exe"
        self.installer.write_bytes(b"signed setup fixture")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def build(self) -> dict[str, object]:
        return build_manifest(
            self.installer,
            version="1.2.3",
            channel="stable",
            manifest_sequence=42,
            signing_key_id="windows-update-2026-01",
            published_at="2026-08-12T00:00:00Z",
            expires_at="2026-08-19T00:00:00Z",
            minimum_updatable_version="1.0.0",
            source_revision="a" * 40,
            rollout_percentage=25,
            rollout_seed="b" * 64,
            installer_url="https://updates.example.test/stable/ChatRoom-1.2.3-Setup.exe",
            authenticode_sha256_thumbprint="c" * 64,
        )

    def test_builds_exact_canonical_bounded_release_metadata(self) -> None:
        first = self.build()
        second = self.build()
        self.assertEqual(first, second)
        self.assertEqual(first["manifestSequence"], 42)
        self.assertEqual(first["architecture"], "x86_64")
        self.assertEqual(first["rollout"], {"percentage": 25, "seed": "b" * 64})
        self.assertEqual(first["installer"]["size"], len(b"signed setup fixture"))

        path = self.root / "manifest.json"
        path.write_bytes(canonical_bytes(first))
        self.assertEqual(read_canonical_manifest(path), first)
        path.write_text(json.dumps(first, indent=2), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "canonical"):
            read_canonical_manifest(path)
        path.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            read_canonical_manifest(path)
        nested = canonical_bytes(first).replace(
            b'"percentage":25', b'"percentage":25,"percentage":25')
        path.write_bytes(nested)
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            read_canonical_manifest(path)

    def test_rejects_unsafe_release_and_rollout_metadata(self) -> None:
        original = self.build()
        mutations = [
            lambda value: value.update(channel="nightly"),
            lambda value: value.update(architecture="arm64"),
            lambda value: value.update(manifestSequence=2**53),
            lambda value: value.update(minimumUpdatableVersion="2.0.0"),
            lambda value: value.update(version="65536.0.0"),
            lambda value: value["installer"].update(size=2 * 1024 * 1024 * 1024 + 1),
            lambda value: value.update(expiresAt="2026-10-19T00:00:00Z"),
            lambda value: value["rollout"].update(percentage=101),
            lambda value: value["installer"].update(url="http://updates.example.test/ChatRoom-1.2.3-Setup.exe"),
            lambda value: value["installer"].update(url="https://evil.test/ChatRoom-9.9.9-Setup.exe"),
            lambda value: value["installer"].update(url="https://updates.example.test/a/../ChatRoom-1.2.3-Setup.exe"),
            lambda value: value["installer"].update(url="https://updates.example.test/a/%2e%2e/ChatRoom-1.2.3-Setup.exe"),
            lambda value: value["installer"].update(authenticodeSha256Thumbprint="short"),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                candidate = json.loads(json.dumps(original))
                mutate(candidate)
                with self.assertRaises(ManifestError):
                    validate_manifest(candidate)

    def test_enforces_manifest_validity_window(self) -> None:
        manifest = self.build()
        validate_manifest(manifest, datetime(2026, 8, 15, tzinfo=timezone.utc))
        for observed in [
            datetime(2026, 8, 11, 23, 59, 59, tzinfo=timezone.utc),
            datetime(2026, 8, 19, tzinfo=timezone.utc),
        ]:
            with self.assertRaisesRegex(ManifestError, "currently valid"):
                validate_manifest(manifest, observed)

    def test_signs_and_verifies_with_ephemeral_ed25519_key(self) -> None:
        manifest_path = self.root / "manifest.json"
        atomic_write(manifest_path, canonical_bytes(self.build()).decode("utf-8"))
        private_key = self.root / "private.pem"
        public_key = self.root / "public.pem"
        signature = self.root / "manifest.sig"
        subprocess.run(
            ["openssl", "genpkey", "-algorithm", "Ed25519", "-out", str(private_key)],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        subprocess.run(
            ["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key)],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )

        sign_manifest(manifest_path, private_key, signature)
        self.assertEqual(signature.stat().st_size, 64)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            sign_manifest(manifest_path, private_key, signature)
        verified = verify_manifest_signature(
            manifest_path,
            signature,
            public_key,
            datetime(2026, 8, 15, tzinfo=timezone.utc),
        )
        self.assertEqual(verified["version"], "1.2.3")

        tampered = self.build()
        tampered["rollout"]["percentage"] = 100
        atomic_write(manifest_path, canonical_bytes(tampered).decode("utf-8"))
        with self.assertRaisesRegex(ManifestError, "signature operation failed"):
            verify_manifest_signature(manifest_path, signature, public_key)

    def test_requires_production_installer_name(self) -> None:
        self.installer.rename(self.root / "ChatRoom-1.2.3-unsigned-verification-Setup.exe")
        self.installer = self.root / "ChatRoom-1.2.3-unsigned-verification-Setup.exe"
        with self.assertRaisesRegex(ManifestError, "production name"):
            self.build()


if __name__ == "__main__":
    unittest.main()
