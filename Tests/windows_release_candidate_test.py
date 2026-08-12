#!/usr/bin/env python3

from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from artifact_manifest_common import ManifestError, sha256_file  # noqa: E402
from windows_release_candidate import assemble_candidate, validate_candidate  # noqa: E402


class WindowsReleaseCandidateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.payload = self.root / "payload"
        (self.payload / "platforms").mkdir(parents=True)
        (self.payload / "sqldrivers").mkdir()
        self.files = {
            "ChatClient.exe": b"signed-client",
            "ChatRoomUpdateLauncher.exe": b"signed-launcher",
            "Qt6Core.dll": b"qt-core",
            "platforms/qwindows.dll": b"windows-platform",
            "sqldrivers/qsqlite.dll": b"sqlite-plugin",
            "libsodium.dll": b"sodium-runtime",
        }
        for relative, content in self.files.items():
            (self.payload / relative).write_bytes(content)
        self.version = "1.2.3"
        self.version_file = self.root / "VERSION"
        self.version_file.write_text(self.version + "\n", encoding="utf-8")
        self.revision = "a" * 40
        self.signer = "b" * 64
        self.now = datetime(2026, 8, 12, 12, 0, 0, tzinfo=timezone.utc)
        self.installer = self.root / f"ChatRoom-{self.version}-Setup.exe"
        self.installer.write_bytes(b"signed-setup")
        self.evidence = self.root / "windows-release-signatures.json"
        self.write_evidence()
        self.candidate = self.root / "candidate"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_evidence(self) -> None:
        roles = (
            ("client", self.payload / "ChatClient.exe"),
            ("update-launcher", self.payload / "ChatRoomUpdateLauncher.exe"),
            ("installer", self.installer),
        )
        artifacts = []
        for role, path in roles:
            digest, size = sha256_file(path)
            artifacts.append({
                "role": role,
                "name": path.name,
                "size": size,
                "sha256": digest,
                "signerCertificateSha256": self.signer,
                "timestampCertificateSha256": "c" * 64,
                "signatureStatus": "valid-timestamped-authenticode",
            })
        self.evidence.write_text(json.dumps({
            "schemaVersion": 1,
            "product": "chat-room-windows-client",
            "version": self.version,
            "sourceRevision": self.revision,
            "architecture": "x86_64",
            "observedAt": "2026-08-12T12:00:00Z",
            "expectedSignerCertificateSha256": self.signer,
            "artifacts": artifacts,
        }), encoding="utf-8")

    def assemble(self):
        return assemble_candidate(
            self.payload, self.installer, self.evidence, self.candidate,
            self.version_file, self.revision, "stable", "6.11.1",
            self.signer, self.now,
        )

    def validate(self):
        return self.validate_root(self.candidate)

    def validate_root(self, root: Path):
        return validate_candidate(
            root, self.version_file, self.revision, "stable",
            "6.11.1", self.signer, self.now,
        )

    def test_atomically_assembles_and_revalidates_complete_candidate(self) -> None:
        assembled = self.assemble()
        verified = self.validate()
        self.assertEqual(assembled["releaseId"], verified["releaseId"])
        self.assertEqual(assembled["assemblyStatus"], "assembled")
        self.assertTrue((self.candidate / "client/Qt6Core.dll").is_file())
        self.assertTrue((self.candidate / "evidence/windows-release-signatures.json").is_file())
        self.assertFalse(any(path.name.startswith(".windows-candidate-")
                             for path in self.root.iterdir()))

    def test_rejects_missing_runtime_forbidden_files_and_existing_destination(self) -> None:
        for relative in ("Qt6Core.dll", "platforms/qwindows.dll",
                         "sqldrivers/qsqlite.dll", "libsodium.dll"):
            with self.subTest(relative=relative):
                path = self.payload / relative
                content = path.read_bytes()
                path.unlink()
                with self.assertRaisesRegex(ManifestError, "required runtime|libsodium"):
                    self.assemble()
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)

        for forbidden in ("ChatServer.exe", "debug.pdb", ".env.production", "release.pem"):
            with self.subTest(forbidden=forbidden):
                path = self.payload / forbidden
                path.write_bytes(b"forbidden")
                with self.assertRaisesRegex(ManifestError, "forbidden"):
                    self.assemble()
                path.unlink()

        self.candidate.mkdir()
        with self.assertRaisesRegex(ManifestError, "already exists"):
            self.assemble()

        self.candidate.rmdir()
        with self.assertRaisesRegex(ManifestError, "overlaps"):
            assemble_candidate(
                self.payload, self.installer, self.evidence,
                self.payload / "nested-candidate", self.version_file,
                self.revision, "stable", "6.11.1", self.signer, self.now)

    def test_rejects_tampering(self) -> None:
        self.assemble()
        (self.candidate / "client/Qt6Core.dll").write_bytes(b"changed")
        with self.assertRaisesRegex(ManifestError, "final bytes changed"):
            self.validate()

    def test_rejects_extra_files_and_identity_changes(self) -> None:
        self.assemble()
        (self.candidate / "client/undeclared.dll").write_bytes(b"extra")
        with self.assertRaisesRegex(ManifestError, "undeclared or missing"):
            self.validate()

        with self.assertRaises(ManifestError):
            validate_candidate(
                self.candidate, self.version_file, self.revision, "beta",
                "6.11.1", self.signer, self.now)

    def test_rejects_manifest_checksum_and_missing_file_mutations(self) -> None:
        self.assemble()
        mutations = []

        def unknown_manifest(root: Path) -> None:
            path = root / "windows-release-candidate.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["unknown"] = True
            path.write_text(json.dumps(value), encoding="utf-8")

        mutations.append(("manifest-shape", unknown_manifest, "unsupported shape"))

        def reordered_manifest(root: Path) -> None:
            path = root / "windows-release-candidate.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["files"].reverse()
            path.write_text(json.dumps(value), encoding="utf-8")

        mutations.append(("manifest-order", reordered_manifest, "not sorted"))

        def duplicate_checksum(root: Path) -> None:
            path = root / "SHA256SUMS"
            first = path.read_text(encoding="utf-8").splitlines()[0]
            path.write_text(first + "\n" + first + "\n", encoding="utf-8")

        mutations.append(("checksums", duplicate_checksum, "malformed"))
        mutations.append((
            "missing-file",
            lambda root: (root / "client/sqldrivers/qsqlite.dll").unlink(),
            "undeclared or missing",
        ))

        for label, mutate, message in mutations:
            with self.subTest(label=label):
                changed = self.root / f"changed-{label}"
                shutil.copytree(self.candidate, changed)
                mutate(changed)
                with self.assertRaisesRegex(ManifestError, message):
                    self.validate_root(changed)

    @unittest.skipIf(os.name == "nt", "Windows symlink creation requires optional privileges")
    def test_rejects_payload_and_candidate_symlinks(self) -> None:
        (self.payload / "linked.dll").symlink_to(self.payload / "Qt6Core.dll")
        with self.assertRaisesRegex(ManifestError, "symbolic links"):
            self.assemble()


if __name__ == "__main__":
    unittest.main()
