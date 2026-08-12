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
from windows_release_evidence import verify_evidence  # noqa: E402


class WindowsReleaseEvidenceTest(unittest.TestCase):
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
            path.write_bytes((role + "-final-bytes").encode())
        self.evidence_path = self.root / "windows-release-signatures.json"
        self.evidence = self.build_evidence()
        self.write_evidence(self.evidence)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def build_evidence(self) -> dict[str, object]:
        artifacts = []
        for role in ("client", "update-launcher", "uninstaller", "installer"):
            digest, size = sha256_file(self.paths[role])
            artifacts.append({
                "role": role,
                "name": self.paths[role].name,
                "size": size,
                "sha256": digest,
                "signerCertificateSha256": self.signer,
                "timestampCertificateSha256": "c" * 64,
                "signatureStatus": "valid-timestamped-authenticode",
            })
        return {
            "schemaVersion": 2,
            "product": "chat-room-windows-client",
            "version": self.version,
            "sourceRevision": self.revision,
            "architecture": "x86_64",
            "observedAt": "2026-08-12T12:00:00Z",
            "expectedSignerCertificateSha256": self.signer,
            "artifacts": artifacts,
        }

    def write_evidence(self, evidence: dict[str, object]) -> None:
        self.evidence_path.write_text(json.dumps(evidence), encoding="utf-8")

    def verify(self):
        return verify_evidence(
            self.evidence_path,
            self.paths["client"],
            self.paths["update-launcher"],
            self.paths["uninstaller"],
            self.paths["installer"],
            self.version_file,
            self.revision,
            self.signer,
            self.now,
        )

    def test_accepts_exact_fresh_evidence_and_final_bytes(self) -> None:
        self.assertEqual(self.verify()["version"], self.version)

    def test_rejects_unknown_shape_identity_and_time(self) -> None:
        for mutate in (
            lambda value: value.update({"unknown": True}),
            lambda value: value.update({"schemaVersion": True}),
            lambda value: value.update({"sourceRevision": "d" * 40}),
            lambda value: value.update({"observedAt": "2026-08-11T11:59:59Z"}),
        ):
            changed = deepcopy(self.evidence)
            mutate(changed)
            self.write_evidence(changed)
            with self.assertRaises(ManifestError):
                self.verify()

        self.evidence_path.write_text(
            '{"schemaVersion":2,"schemaVersion":2}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            self.verify()

    def test_rejects_changed_bytes_signer_timestamp_and_role_order(self) -> None:
        self.paths["client"].write_bytes(b"changed")
        with self.assertRaises(ManifestError):
            self.verify()
        self.paths["client"].write_bytes(b"client-final-bytes")

        for label, mutate in (
            ("signer", lambda value: value["artifacts"][1].update(
                {"signerCertificateSha256": "d" * 64})),
            ("timestamp", lambda value: value["artifacts"][2].update(
                {"timestampCertificateSha256": ""})),
            ("boolean-size", lambda value: value["artifacts"][0].update(
                {"size": True})),
            ("role-order", lambda value: value["artifacts"].reverse()),
        ):
            with self.subTest(label=label):
                changed = self.build_evidence()
                mutate(changed)
                self.write_evidence(changed)
                with self.assertRaises(ManifestError):
                    self.verify()


if __name__ == "__main__":
    unittest.main()
