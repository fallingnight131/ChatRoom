#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError, sha256_file  # noqa: E402
from windows_install_evidence import verify_install_evidence  # noqa: E402


class WindowsInstallEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.version = "1.2.3"
        self.revision = "a" * 40
        self.signer = "b" * 64
        self.now = datetime(2026, 8, 12, 12, 0, 0, tzinfo=timezone.utc)
        self.version_file = self.root / "VERSION"
        self.version_file.write_text(self.version + "\n", encoding="utf-8")
        self.paths = {
            "client": self.root / "ChatClient.exe",
            "update-launcher": self.root / "ChatRoomUpdateLauncher.exe",
            "uninstaller": self.root / f"ChatRoom-{self.version}-Uninstall.exe",
            "installer": self.root / f"ChatRoom-{self.version}-Setup.exe",
        }
        for role, path in self.paths.items():
            path.write_bytes((role + "-bytes").encode())
        self.evidence_path = self.root / "windows-install-acceptance.json"
        self.evidence = self.build_evidence()
        self.write(self.evidence)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def entry(self, role: str, name=None) -> dict[str, object]:
        digest, size = sha256_file(self.paths[role])
        return {"role": role, "name": name or self.paths[role].name,
                "size": size, "sha256": digest}

    def build_evidence(self) -> dict[str, object]:
        return {
            "schemaVersion": 1,
            "evidenceType": "windows-native-install-acceptance",
            "status": "install-uninstall-observed",
            "product": "chat-room-windows-client",
            "version": self.version,
            "sourceRevision": self.revision,
            "architecture": "x86_64",
            "observedAt": "2026-08-12T12:00:00Z",
            "expectedSignerCertificateSha256": self.signer,
            "sourceArtifacts": [self.entry(role) for role in
                                ("client", "update-launcher", "uninstaller", "installer")],
            "installedArtifacts": [
                self.entry("client"), self.entry("update-launcher"),
                self.entry("uninstaller", "Uninstall.exe"),
            ],
            "installExitCode": 0,
            "uninstallExitCode": 0,
            "registrationMatched": True,
            "installRootRemoved": True,
            "temporaryPathsRemoved": True,
            "registrationRemoved": True,
        }

    def write(self, value: dict[str, object]) -> None:
        self.evidence_path.write_text(json.dumps(value), encoding="utf-8")

    def verify(self):
        return verify_install_evidence(
            self.evidence_path, self.paths["client"], self.paths["update-launcher"],
            self.paths["uninstaller"], self.paths["installer"], self.version_file,
            self.revision, self.signer, self.now,
        )

    def test_accepts_closed_fresh_success_evidence(self) -> None:
        self.assertEqual(self.verify()["status"], "install-uninstall-observed")

    def test_rejects_identity_result_and_order_mutations(self) -> None:
        mutations = (
            lambda value: value.update({"unknown": True}),
            lambda value: value.update({"installExitCode": True}),
            lambda value: value.update({"registrationRemoved": False}),
            lambda value: value["sourceArtifacts"].reverse(),
            lambda value: value["installedArtifacts"][2].update({"name": "Other.exe"}),
            lambda value: value.update({"observedAt": "2026-08-11T11:59:59Z"}),
        )
        for mutate in mutations:
            changed = deepcopy(self.evidence)
            mutate(changed)
            self.write(changed)
            with self.assertRaises(ManifestError):
                self.verify()

    def test_rejects_source_and_installed_byte_mismatch(self) -> None:
        changed = deepcopy(self.evidence)
        changed["installedArtifacts"][0]["sha256"] = "c" * 64
        self.write(changed)
        with self.assertRaisesRegex(ManifestError, "did not match"):
            self.verify()

        self.write(self.evidence)
        self.paths["installer"].write_bytes(b"changed")
        with self.assertRaisesRegex(ManifestError, "source bytes changed"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
