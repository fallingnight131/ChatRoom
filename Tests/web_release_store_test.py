#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from artifact_manifest_common import ManifestError  # noqa: E402
from web_artifact_manifest import build_manifest, write_manifest  # noqa: E402
from web_release_store import activate_release, inspect_active_release, stage_release  # noqa: E402


class WebReleaseStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.store = self.root / "store"
        self.policy_source = ROOT / "packaging" / "web" / "response-policy.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def artifact(self, version: str, revision: str, body: str) -> Path:
        artifact = self.root / f"artifact-{version}"
        site = artifact / "site"
        assets = site / "assets"
        assets.mkdir(parents=True)
        (assets / "index-AbCd1234.js").write_text(body, encoding="utf-8")
        (site / "index.html").write_text(
            '<script type="module" src="/assets/index-AbCd1234.js"></script>\n',
            encoding="utf-8",
        )
        package = artifact / "package.json"
        package.write_text(json.dumps({"name": "chatroom-web", "version": version, "private": True}))
        (artifact / "package-lock.json").write_text(json.dumps({
            "name": "chatroom-web", "version": version,
            "packages": {"": {"name": "chatroom-web", "version": version}},
        }))
        policy = artifact / "response-policy.json"
        policy.write_bytes(self.policy_source.read_bytes())
        manifest, checksums = build_manifest(site, package, revision, policy)
        package.unlink()
        (artifact / "package-lock.json").unlink()
        write_manifest(artifact, manifest, checksums)
        return artifact

    def test_stages_upgrades_and_rolls_back_by_atomic_pointer_without_rebuild(self) -> None:
        first_artifact = self.artifact("1.0.0", "a" * 40, "console.log('first')\n")
        second_artifact = self.artifact("1.1.0", "b" * 40, "console.log('second')\n")

        first = stage_release(first_artifact, self.store)
        self.assertEqual(stage_release(first_artifact, self.store)["stageStatus"], "already-present")
        first_file = self.store / "releases" / first["releaseId"] / "site/assets/index-AbCd1234.js"
        original_bytes = first_file.read_bytes()
        activate_release(self.store, first["releaseId"], "2026-08-12T00:00:00+00:00")
        self.assertEqual(inspect_active_release(self.store)["version"], "1.0.0")

        second = stage_release(second_artifact, self.store)
        activate_release(self.store, second["releaseId"], "2026-08-12T00:01:00+00:00")
        self.assertEqual(inspect_active_release(self.store)["version"], "1.1.0")

        activate_release(self.store, first["releaseId"], "2026-08-12T00:02:00+00:00")
        health = inspect_active_release(self.store)
        self.assertEqual(health["status"], "healthy")
        self.assertEqual(health["version"], "1.0.0")
        self.assertEqual(first_file.read_bytes(), original_bytes)
        self.assertTrue((self.store / "releases" / second["releaseId"]).is_dir())

    def test_rejects_tampering_extras_and_pointer_identity_mismatch(self) -> None:
        artifact = self.artifact("1.0.0", "c" * 40, "console.log('safe')\n")
        staged = stage_release(artifact, self.store)
        release = self.store / "releases" / staged["releaseId"]
        activate_release(self.store, staged["releaseId"], "2026-08-12T00:00:00+00:00")
        pointer_path = self.store / "active-release.json"
        pointer = json.loads(pointer_path.read_text(encoding="utf-8"))
        pointer["sourceRevision"] = "e" * 40
        pointer_path.write_text(json.dumps(pointer), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "pointer does not match"):
            inspect_active_release(self.store)

        (release / "site/assets/index-AbCd1234.js").write_text("tampered", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "integrity"):
            activate_release(self.store, staged["releaseId"])

    def test_rejects_undeclared_artifact_files_and_missing_release(self) -> None:
        artifact = self.artifact("1.0.0", "d" * 40, "console.log('safe')\n")
        (artifact / "secret.env").write_text("must-not-deploy", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "undeclared"):
            stage_release(artifact, self.store)
        with self.assertRaisesRegex(ManifestError, "real directory"):
            activate_release(self.store, "missing")


if __name__ == "__main__":
    unittest.main()
