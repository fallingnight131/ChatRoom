#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from artifact_manifest_common import ManifestError  # noqa: E402
from verify_windows_unsigned_artifact import verify  # noqa: E402
from windows_artifact_manifest import build_manifest, write_manifest  # noqa: E402


class WindowsUnsignedArtifactVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.artifact = self.root / "artifact"
        self.payload = self.artifact / "client"
        files = {
            "ChatClient.exe": b"client",
            "ChatRoomUpdateLauncher.exe": b"launcher",
            "Qt6Core.dll": b"qt-core",
            "libsodium.dll": b"sodium",
            "platforms/qwindows.dll": b"platform",
            "sqldrivers/qsqlite.dll": b"sqlite",
        }
        for relative, content in files.items():
            path = self.payload / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        self.version_file = self.root / "VERSION"
        self.version_file.write_text("1.2.3\n", encoding="utf-8")
        self.revision = "a" * 40
        installer = self.artifact / "installer/ChatRoom-1.2.3-unsigned-verification-Setup.exe"
        installer.parent.mkdir()
        installer.write_bytes(b"unsigned-setup")
        candidate = {}
        for path in self.payload.rglob("*"):
            if path.is_file():
                content = path.read_bytes()
                candidate[path.relative_to(self.payload).as_posix()] = {
                    "size": len(content),
                    "sha256": hashlib.sha256(content).hexdigest(),
                }
        baseline = {name: dict(entry) for name, entry in candidate.items()}
        baseline["ChatClient.exe"] = {"size": 8, "sha256": "b" * 64}
        baseline["ChatRoomUpdateLauncher.exe"] = {"size": 8, "sha256": "c" * 64}
        parity = self.artifact / "cmake-payload-parity.json"
        parity.write_text(json.dumps({
            "schemaVersion": 1,
            "version": "1.2.3",
            "sourceRevision": self.revision,
            "baselineBuildSystem": "qmake",
            "candidateBuildSystem": "cmake",
            "runtimeBytesEquivalent": True,
            "executableByteDifferencesAllowed": [
                "ChatClient.exe", "ChatRoomUpdateLauncher.exe"],
            "baseline": baseline,
            "candidate": candidate,
        }), encoding="utf-8")
        manifest, checksum_lines = build_manifest(
            self.payload, self.version_file, self.revision, "6.11.1",
            installer, parity, "cmake")
        write_manifest(self.artifact, manifest, checksum_lines)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def verify(self, root: Path | None = None):
        return verify(root or self.artifact, self.version_file, self.revision, "6.11.1")

    def copy(self, name: str) -> Path:
        target = self.root / name
        shutil.copytree(self.artifact, target)
        return target

    def test_accepts_complete_schema_three_cmake_artifact(self) -> None:
        result = self.verify()
        self.assertEqual(result["buildSystem"], "cmake")
        self.assertEqual(result["verificationStatus"],
                         "unsigned-artifact-verified-for-protected-signing")

    def test_rejects_byte_missing_and_extra_file_mutations(self) -> None:
        changed = self.copy("changed")
        (changed / "client/Qt6Core.dll").write_bytes(b"changed")
        with self.assertRaisesRegex(ManifestError, "final bytes changed"):
            self.verify(changed)
        missing = self.copy("missing")
        (missing / "client/Qt6Core.dll").unlink()
        with self.assertRaisesRegex(ManifestError, "undeclared or missing"):
            self.verify(missing)
        extra = self.copy("extra")
        (extra / "client/debug.pdb").write_bytes(b"debug")
        with self.assertRaisesRegex(ManifestError, "undeclared or missing"):
            self.verify(extra)

    def test_rejects_manifest_identity_shape_and_checksum_mutations(self) -> None:
        wrong = self.copy("wrong-build")
        manifest_path = wrong / "artifact-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["buildSystem"] = "qmake"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "identity"):
            self.verify(wrong)

        opened = self.copy("open-schema")
        manifest_path = opened / "artifact-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["unexpected"] = True
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "identity"):
            self.verify(opened)

        duplicate = self.copy("duplicate-checksum")
        checksum_path = duplicate / "SHA256SUMS"
        first = checksum_path.read_text(encoding="ascii").splitlines()[0]
        checksum_path.write_text(first + "\n" + first + "\n", encoding="ascii")
        with self.assertRaisesRegex(ManifestError, "malformed"):
            self.verify(duplicate)

    def test_rejects_requested_identity_mismatch(self) -> None:
        with self.assertRaisesRegex(ManifestError, "identity"):
            verify(self.artifact, self.version_file, "b" * 40, "6.11.1")
        with self.assertRaisesRegex(ManifestError, "Qt version"):
            verify(self.artifact, self.version_file, self.revision, "latest")

    @unittest.skipIf(os.name == "nt", "Windows symlink creation requires privileges")
    def test_rejects_symbolic_links(self) -> None:
        linked = self.copy("linked")
        (linked / "linked.dll").symlink_to(linked / "client/Qt6Core.dll")
        with self.assertRaisesRegex(ManifestError, "symbolic link"):
            self.verify(linked)


if __name__ == "__main__":
    unittest.main()
