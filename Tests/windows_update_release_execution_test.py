#!/usr/bin/env python3

from __future__ import annotations

import json
import os
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError, atomic_write  # noqa: E402
from windows_update_channel_candidate import assemble_candidate  # noqa: E402
from windows_update_channel_store import stage_release  # noqa: E402
from windows_update_manifest import sign_manifest  # noqa: E402
from windows_update_release_authorization import write_once  # noqa: E402
from windows_update_release_authorization_test import (  # noqa: E402
    WindowsUpdateReleaseAuthorizationTest,
)
from windows_update_release_execution import (  # noqa: E402
    execute, inspect_active, verify_execution,
)
from windows_update_incident_state import open_incident  # noqa: E402


class WindowsUpdateReleaseExecutionTest(WindowsUpdateReleaseAuthorizationTest):
    def setUp(self) -> None:
        super().setUp()
        self.execution = self.root / "execution.json"
        self.current_candidate = self.root / "current-update-candidate"
        self.store = self.root / "execution-store"

    def prepare_execution(self) -> None:
        if self.execution.parent.joinpath("prepared.marker").exists():
            return
        value = self.create()
        write_once(self.authorization, value)
        current_signature = self.root / "current-manifest.json.sig"
        sign_manifest(self.current_manifest, self.private_key, current_signature)
        assemble_candidate(
            self.candidate, self.current_manifest, current_signature,
            self.public_key, self.current_candidate, self.version_file,
            self.revision, "stable", "6.11.1", self.signer,
            self.public_digest(), self.now,
        )
        target = stage_release(
            self.update_candidate, self.store, self.version_file, self.revision,
            "stable", "6.11.1", self.signer, self.public_digest(), self.now,
        )
        current = stage_release(
            self.current_candidate, self.store, self.version_file, self.revision,
            "stable", "6.11.1", self.signer, self.public_digest(), self.now,
        )
        pointer = {
            "schemaVersion": 1,
            "channel": "stable",
            "releaseId": current["releaseId"],
            "manifestSequence": current["manifestSequence"],
            "version": current["version"],
            "sourceRevision": current["sourceRevision"],
            "activatedAt": "2026-08-12T11:59:00Z",
        }
        atomic_write(
            self.store / "active-channel.json",
            json.dumps(pointer, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
        )
        self.target_release = self.store / "releases" / str(target["releaseId"])
        self.rollback_release = self.store / "releases" / str(current["releaseId"])
        (self.execution.parent / "prepared.marker").write_text("ok", encoding="utf-8")

    def run_execution(self, output=None):
        self.prepare_execution()
        return execute(
            self.authorization, self.target_release, self.current_manifest,
            self.version_file, self.revision, "stable", "6.11.1", self.signer,
            self.public_digest(), self.store, output or self.execution, self.now,
        )

    def test_consumes_once_and_atomically_switches_to_pre_staged_release(self) -> None:
        result = self.run_execution()
        self.assertEqual(result["status"],
                         "channel-pointer-switched-awaiting-external-observation")
        self.assertEqual(inspect_active(self.store, self.now)["releaseId"],
                         result["releaseId"])
        self.assertEqual(verify_execution(
            self.execution, self.authorization, self.target_release,
            self.rollback_release, self.current_manifest, self.version_file,
            self.revision, "stable", "6.11.1", self.signer,
            self.public_digest(),
        ), result)
        pointer = json.loads((self.store / "active-channel.json").read_text(encoding="utf-8"))
        pointer.update({
            "releaseId": result["rollbackReleaseId"],
            "manifestSequence": result["rollbackManifestSequence"],
        })
        atomic_write(self.store / "active-channel.json",
                     json.dumps(pointer, sort_keys=True) + "\n")
        with self.assertRaisesRegex(ManifestError, "already exists"):
            self.run_execution(self.root / "second-execution.json")

    def test_restores_previous_pointer_when_evidence_commit_fails(self) -> None:
        self.prepare_execution()
        before = inspect_active(self.store, self.now)
        self.execution.write_text("occupied", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "already exists"):
            self.run_execution()
        after = inspect_active(self.store, self.now)
        self.assertEqual(after["releaseId"], before["releaseId"])

    def test_rejects_wrong_active_pointer_before_consumption(self) -> None:
        self.prepare_execution()
        pointer = json.loads((self.store / "active-channel.json").read_text(encoding="utf-8"))
        pointer["manifestSequence"] = 40
        atomic_write(self.store / "active-channel.json",
                     json.dumps(pointer, sort_keys=True) + "\n")
        with self.assertRaises(ManifestError):
            self.run_execution()
        self.assertFalse((self.store / ".promotion-consumptions").exists())

    def test_rejects_mutated_execution_evidence(self) -> None:
        self.run_execution()
        value = json.loads(self.execution.read_text(encoding="utf-8"))
        value["status"] = "published"
        self.execution.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "does not match"):
            verify_execution(
                self.execution, self.authorization, self.target_release,
                self.rollback_release, self.current_manifest, self.version_file,
                self.revision, "stable", "6.11.1", self.signer,
                self.public_digest(),
            )

    def test_rejects_general_promotion_while_rollout_incident_is_open(self) -> None:
        self.prepare_execution()
        open_incident(
            self.store, self.authorization, "stable", "c" * 64, "d" * 64,
            self.now)
        with self.assertRaisesRegex(ManifestError, "dedicated forward-fix"):
            self.run_execution()
        self.assertFalse((self.store / ".promotion-consumptions").exists())

    @__import__("unittest").skipIf(os.name == "nt", "Windows symlink needs privileges")
    def test_rejects_symlinked_consumption_boundary(self) -> None:
        self.prepare_execution()
        outside = self.root / "outside-consumption"
        outside.mkdir()
        (self.store / ".promotion-consumptions").symlink_to(
            outside, target_is_directory=True)
        with self.assertRaisesRegex(ManifestError, "directory is unsafe"):
            self.run_execution()


if __name__ == "__main__":
    import unittest
    unittest.main()
