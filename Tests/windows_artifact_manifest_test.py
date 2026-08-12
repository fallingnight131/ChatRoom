#!/usr/bin/env python3

from __future__ import annotations

import json
import hashlib
import os
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from windows_artifact_manifest import ManifestError, build_manifest, write_manifest  # noqa: E402


class WindowsArtifactManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.payload = self.root / "client"
        (self.payload / "platforms").mkdir(parents=True)
        (self.payload / "ChatClient.exe").write_bytes(b"client")
        (self.payload / "platforms" / "qwindows.dll").write_bytes(b"plugin")
        self.version_file = self.root / "VERSION"
        self.version_file.write_text("1.2.3\n", encoding="utf-8")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def build(self):
        return build_manifest(
            self.payload,
            self.version_file,
            "a" * 40,
            "6.11.1",
        )

    def test_builds_sorted_deterministic_unsigned_client_manifest(self) -> None:
        first, checksums = self.build()
        second, repeated_checksums = self.build()

        self.assertEqual(first, second)
        self.assertEqual(checksums, repeated_checksums)
        self.assertEqual(first["version"], "1.2.3")
        self.assertEqual(first["buildSystem"], "cmake")
        self.assertEqual(first["signatureStatus"], "unsigned-verification-only")
        self.assertEqual(
            [entry["path"] for entry in first["files"]],
            ["client/ChatClient.exe", "client/platforms/qwindows.dll"],
        )
        self.assertTrue(all("server" not in line.lower() for line in checksums))

        output = self.root / "artifact"
        write_manifest(output, first, checksums)
        parsed = json.loads((output / "artifact-manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(parsed, first)
        self.assertEqual((output / "SHA256SUMS").read_text(encoding="utf-8").count("\n"), 2)

    def test_records_only_the_expected_unsigned_nsis_installer(self) -> None:
        installer = self.root / "ChatRoom-1.2.3-beta-Setup.exe"
        installer.write_bytes(b"wrong")
        with self.assertRaisesRegex(ManifestError, "installer path or name"):
            build_manifest(self.payload, self.version_file, "a" * 40, "6.11.1", installer)

        installer = self.root / "ChatRoom-1.2.3-unsigned-verification-Setup.exe"
        installer.write_bytes(b"setup")
        manifest, checksums = build_manifest(
            self.payload, self.version_file, "a" * 40, "6.11.1", installer,
        )
        self.assertEqual(manifest["schemaVersion"], 3)
        self.assertEqual(manifest["installer"]["format"], "nsis")
        self.assertEqual(manifest["installer"]["signatureStatus"], "unsigned-verification-only")
        self.assertTrue(checksums[-1].endswith("installer/ChatRoom-1.2.3-unsigned-verification-Setup.exe"))

    def test_binds_closed_cmake_payload_parity_evidence(self) -> None:
        additions = {
            "ChatRoomUpdateLauncher.exe": b"launcher",
            "libsodium-26.dll": b"sodium",
            "sqldrivers/qsqlite.dll": b"sqlite",
        }
        for name, content in additions.items():
            path = self.payload / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        candidate = {}
        for path in self.payload.rglob("*"):
            if path.is_file():
                content = path.read_bytes()
                candidate[path.relative_to(self.payload).as_posix()] = {
                    "size": len(content),
                    "sha256": hashlib.sha256(content).hexdigest(),
                }
        baseline = {name: dict(entry) for name, entry in candidate.items()}
        baseline["ChatClient.exe"] = {"size": 8, "sha256": "c" * 64}
        baseline["ChatRoomUpdateLauncher.exe"] = {
            "size": 8, "sha256": "c" * 64}
        evidence = self.root / "cmake-payload-parity.json"
        document = {
            "schemaVersion": 1,
            "version": "1.2.3",
            "sourceRevision": "a" * 40,
            "baselineBuildSystem": "qmake",
            "candidateBuildSystem": "cmake",
            "runtimeBytesEquivalent": True,
            "executableByteDifferencesAllowed": [
                "ChatClient.exe", "ChatRoomUpdateLauncher.exe"],
            "baseline": baseline,
            "candidate": {name: dict(entry) for name, entry in candidate.items()},
        }
        evidence.write_text(json.dumps(document), encoding="utf-8")
        manifest, checksums = build_manifest(
            self.payload, self.version_file, "a" * 40, "6.11.1",
            cmake_payload_parity=evidence,
        )
        self.assertTrue(manifest["cmakePayloadParity"]["runtimeBytesEquivalent"])
        self.assertTrue(checksums[-1].endswith("cmake-payload-parity.json"))

        document["candidate"]["platforms/qwindows.dll"] = {
            "size": 7, "sha256": "e" * 64}
        evidence.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "runtime evidence differs"):
            build_manifest(
                self.payload, self.version_file, "a" * 40, "6.11.1",
                cmake_payload_parity=evidence,
            )

        document["candidate"] = {
            name: dict(entry) for name, entry in candidate.items()}
        document["candidate"]["ChatClient.exe"] = {
            "size": 9, "sha256": "d" * 64}
        evidence.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "canonical payload does not match"):
            build_manifest(
                self.payload, self.version_file, "a" * 40, "6.11.1",
                cmake_payload_parity=evidence,
            )

        document["candidate"] = {
            name: dict(entry) for name, entry in candidate.items()}
        document["unexpected"] = True
        evidence.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "policy rejected"):
            build_manifest(
                self.payload, self.version_file, "a" * 40, "6.11.1",
                cmake_payload_parity=evidence,
            )

    def test_rejects_noncanonical_identity_and_empty_payload(self) -> None:
        self.version_file.write_text(" 1.2.3\n", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "SemVer"):
            self.build()
        self.version_file.write_text("1.2.3-beta.1\n", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "SemVer"):
            self.build()

        self.version_file.write_text("1.2.3\n", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "Git SHA"):
            build_manifest(self.payload, self.version_file, "ABC", "6.11.1")
        with self.assertRaisesRegex(ManifestError, "Qt version"):
            build_manifest(self.payload, self.version_file, "a" * 40, "latest")
        with self.assertRaisesRegex(ManifestError, "build system"):
            build_manifest(
                self.payload, self.version_file, "a" * 40, "6.11.1",
                build_system="ninja",
            )

        for path in sorted(self.payload.rglob("*"), reverse=True):
            if path.is_file():
                path.unlink()
            else:
                path.rmdir()
        with self.assertRaisesRegex(ManifestError, "must not be empty"):
            self.build()

    @unittest.skipIf(os.name == "nt", "Windows symlink creation requires optional privileges")
    def test_rejects_symbolic_links(self) -> None:
        (self.payload / "linked.dll").symlink_to(self.payload / "ChatClient.exe")
        with self.assertRaisesRegex(ManifestError, "symbolic links"):
            self.build()


if __name__ == "__main__":
    unittest.main()
