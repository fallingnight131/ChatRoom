#!/usr/bin/env python3

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from artifact_manifest_common import ManifestError, sha256_file  # noqa: E402
from windows_release_candidate import assemble_candidate, validate_candidate  # noqa: E402
from windows_protected_release_intent import create as create_signing_intent  # noqa: E402
from windows_update_product_trust_evidence import build_evidence as build_trust_evidence  # noqa: E402
from windows_update_product_trust_intent import create_intent as create_trust_intent  # noqa: E402


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
        self.uninstaller = self.root / f"ChatRoom-{self.version}-Uninstall.exe"
        self.uninstaller.write_bytes(b"signed-uninstaller")
        self.evidence = self.root / "windows-release-signatures.json"
        self.write_evidence()
        self.install_evidence = self.root / "windows-install-acceptance.json"
        self.write_install_evidence()
        self.intent = self.root / "protected-signing-intent.json"
        self.intent.write_text(json.dumps(create_signing_intent(
            self.version_file, self.revision, "stable", "123456789",
            "d" * 40, self.signer,
            "https://timestamp.example.test/rfc3161", self.now)), encoding="utf-8")
        trust_private = self.root / "product-trust-private.pem"
        self.product_trust_public = self.root / "product-trust-public.pem"
        subprocess.run(
            ["openssl", "genpkey", "-algorithm", "Ed25519", "-out", str(trust_private)],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(
            ["openssl", "pkey", "-in", str(trust_private), "-pubout",
             "-out", str(self.product_trust_public)],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.product_trust_url = (
            "https://updates.example.test/windows/stable/manifest.json")
        self.product_trust_intent = self.root / "product-update-trust-intent.json"
        trust_intent = create_trust_intent(
            self.version_file, self.revision, "stable", self.product_trust_url,
            "windows-update-2026-01", self.product_trust_public, self.now)
        self.product_trust_intent.write_text(
            json.dumps(trust_intent), encoding="utf-8")
        self.product_trust_diagnostic = self.root / "signed-trust-diagnostic.json"
        self.product_trust_diagnostic.write_text(json.dumps({
            "schemaVersion": 1,
            "product": "chat-room-windows-client",
            "enabled": True,
            "channel": "stable",
            "manifestUrl": self.product_trust_url,
            "signatureUrl": self.product_trust_url + ".sig",
            "trustedKeys": [{
                "keyId": trust_intent["primaryKey"]["keyId"],
                "publicKeyHex": trust_intent["primaryKey"]["publicKeyHex"],
            }],
            "error": "",
        }), encoding="utf-8")
        self.product_trust_evidence = self.root / "signed-trust-evidence.json"
        self.product_trust_evidence.write_text(json.dumps(build_trust_evidence(
            self.payload / "ChatClient.exe", self.product_trust_diagnostic,
            self.product_trust_intent, self.version_file, self.revision,
            "stable", self.product_trust_url, "windows-update-2026-01",
            self.product_trust_public, self.now,
        )), encoding="utf-8")
        self.candidate = self.root / "candidate"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_evidence(self) -> None:
        roles = (
            ("client", self.payload / "ChatClient.exe"),
            ("update-launcher", self.payload / "ChatRoomUpdateLauncher.exe"),
            ("uninstaller", self.uninstaller),
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
            "schemaVersion": 2,
            "product": "chat-room-windows-client",
            "version": self.version,
            "sourceRevision": self.revision,
            "architecture": "x86_64",
            "observedAt": "2026-08-12T12:00:00Z",
            "expectedSignerCertificateSha256": self.signer,
            "artifacts": artifacts,
        }), encoding="utf-8")

    def write_install_evidence(self) -> None:
        roles = (
            ("client", self.payload / "ChatClient.exe"),
            ("update-launcher", self.payload / "ChatRoomUpdateLauncher.exe"),
            ("uninstaller", self.uninstaller),
            ("installer", self.installer),
        )
        source = []
        installed = []
        for role, path in roles:
            digest, size = sha256_file(path)
            source.append({"role": role, "name": path.name, "size": size, "sha256": digest})
            if role != "installer":
                installed.append({"role": role,
                                  "name": "Uninstall.exe" if role == "uninstaller" else path.name,
                                  "size": size, "sha256": digest})
        self.install_evidence.write_text(json.dumps({
            "schemaVersion": 1,
            "evidenceType": "windows-native-install-acceptance",
            "status": "install-uninstall-observed",
            "product": "chat-room-windows-client",
            "version": self.version,
            "sourceRevision": self.revision,
            "architecture": "x86_64",
            "observedAt": "2026-08-12T12:00:00Z",
            "expectedSignerCertificateSha256": self.signer,
            "sourceArtifacts": source,
            "installedArtifacts": installed,
            "installExitCode": 0,
            "uninstallExitCode": 0,
            "registrationMatched": True,
            "installRootRemoved": True,
            "temporaryPathsRemoved": True,
            "registrationRemoved": True,
        }), encoding="utf-8")

    def assemble(self):
        return assemble_candidate(
            self.payload, self.uninstaller, self.installer, self.evidence,
            self.intent, self.install_evidence,
            self.product_trust_intent, self.product_trust_diagnostic,
            self.product_trust_evidence, self.product_trust_public, None,
            self.candidate,
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
        self.assertTrue((self.candidate / "evidence/protected-signing-intent.json").is_file())
        self.assertTrue((self.candidate / "evidence/windows-install-acceptance.json").is_file())
        self.assertTrue((self.candidate / "evidence/signed-product-update-trust-evidence.json").is_file())
        self.assertEqual(
            (self.candidate / f"installer/ChatRoom-{self.version}-Uninstall.exe").read_bytes(),
            b"signed-uninstaller",
        )
        manifest = json.loads((self.candidate / "windows-release-candidate.json").read_text(
            encoding="utf-8"))
        self.assertEqual(manifest["schemaVersion"], 6)
        self.assertEqual(manifest["productUpdateTrust"]["keyIds"], [
            "windows-update-2026-01"])
        self.assertEqual(manifest["assembledAt"], "2026-08-12T12:00:00Z")
        self.assertFalse(any(path.name.startswith(".windows-candidate-")
                             for path in self.root.iterdir()))

    def test_revalidates_archived_candidate_against_assembly_time(self) -> None:
        self.assemble()
        verified = validate_candidate(
            self.candidate, self.version_file, self.revision, "stable",
            "6.11.1", self.signer, self.now + timedelta(days=90),
        )
        self.assertEqual(verified["releaseId"],
                         f"windows-stable-{self.version}-{self.revision}")

        manifest_path = self.candidate / "windows-release-candidate.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["assembledAt"] = "2027-01-01T00:00:00Z"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "from the future"):
            self.validate()

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
                self.payload, self.uninstaller, self.installer,
                self.evidence, self.intent, self.install_evidence,
                self.product_trust_intent, self.product_trust_diagnostic,
                self.product_trust_evidence, self.product_trust_public, None,
                self.payload / "nested-candidate", self.version_file,
                self.revision, "stable", "6.11.1", self.signer, self.now)

    def test_rejects_tampering(self) -> None:
        self.assemble()
        changed = self.root / "changed-uninstaller"
        shutil.copytree(self.candidate, changed)
        (changed / f"installer/ChatRoom-{self.version}-Uninstall.exe").write_bytes(
            b"different-uninstaller"
        )
        with self.assertRaisesRegex(ManifestError, "final bytes changed"):
            self.validate_root(changed)

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

    def test_rejects_protected_signing_intent_mutation(self) -> None:
        self.assemble()
        path = self.candidate / "evidence/protected-signing-intent.json"
        intent = json.loads(path.read_text(encoding="utf-8"))
        intent["environment"] = "unprotected"
        path.write_text(json.dumps(intent), encoding="utf-8")
        manifest_path = self.candidate / "windows-release-candidate.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        for entry in manifest["files"]:
            if entry["path"] == "evidence/protected-signing-intent.json":
                entry["sha256"], entry["size"] = sha256_file(path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        checksum_path = self.candidate / "SHA256SUMS"
        lines = []
        for line in checksum_path.read_text(encoding="utf-8").splitlines():
            if line.endswith("  evidence/protected-signing-intent.json"):
                digest, _ = sha256_file(path)
                line = f"{digest}  evidence/protected-signing-intent.json"
            lines.append(line)
        checksum_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "intent identity"):
            self.validate()

    def test_rejects_rehashed_install_acceptance_mutation(self) -> None:
        self.assemble()
        relative = "evidence/windows-install-acceptance.json"
        path = self.candidate / relative
        evidence = json.loads(path.read_text(encoding="utf-8"))
        evidence["registrationRemoved"] = False
        path.write_text(json.dumps(evidence), encoding="utf-8")
        manifest_path = self.candidate / "windows-release-candidate.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        for entry in manifest["files"]:
            if entry["path"] == relative:
                entry["sha256"], entry["size"] = sha256_file(path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        checksum_path = self.candidate / "SHA256SUMS"
        digest, _ = sha256_file(path)
        lines = [
            f"{digest}  {relative}" if line.endswith(f"  {relative}") else line
            for line in checksum_path.read_text(encoding="utf-8").splitlines()
        ]
        checksum_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "identity or result"):
            self.validate()

    def test_rejects_rehashed_signed_product_trust_mutation(self) -> None:
        self.assemble()
        relative = "evidence/signed-product-update-trust-diagnostic.json"
        path = self.candidate / relative
        diagnostic = json.loads(path.read_text(encoding="utf-8"))
        diagnostic["manifestUrl"] = (
            "https://updates.example.test/windows/beta/manifest.json")
        path.write_text(json.dumps(diagnostic), encoding="utf-8")
        manifest_path = self.candidate / "windows-release-candidate.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        for entry in manifest["files"]:
            if entry["path"] == relative:
                entry["sha256"], entry["size"] = sha256_file(path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        checksums_path = self.candidate / "SHA256SUMS"
        digest, _ = sha256_file(path)
        lines = [
            f"{digest}  {relative}" if line.endswith(f"  {relative}") else line
            for line in checksums_path.read_text(encoding="utf-8").splitlines()
        ]
        checksums_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "does not match"):
            self.validate()

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
