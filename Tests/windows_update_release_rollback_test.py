#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
from datetime import timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError, atomic_write  # noqa: E402
from windows_update_release_completion import write_once  # noqa: E402
from windows_update_release_completion_test import (  # noqa: E402
    WindowsUpdateReleaseCompletionTest,
)
from windows_update_release_execution import inspect_active  # noqa: E402
from windows_update_release_rollback import (  # noqa: E402
    execute_rollback, verify_rollback,
)


class WindowsUpdateReleaseRollbackTest(WindowsUpdateReleaseCompletionTest):
    def setUp(self) -> None:
        super().setUp()
        self.rollback_evidence = self.root / "rollback-execution.json"
        self.rollback_now = self.now + timedelta(minutes=3)

    def prepare_rollback(self) -> None:
        if not self.completion.exists():
            write_once(self.completion, self.build())

    def run_rollback(self, output=None):
        self.prepare_rollback()
        return execute_rollback(
            self.completion, self.execution, self.authorization,
            self.target_release, self.rollback_release, self.current_manifest,
            self.version_file, self.revision, "stable", "6.11.1", self.signer,
            self.public_digest(), self.observation, self.store,
            output or self.rollback_evidence, self.rollback_now,
        )

    def verify_rollback_file(self):
        return verify_rollback(
            self.rollback_evidence, self.completion, self.execution,
            self.authorization, self.target_release, self.rollback_release,
            self.current_manifest, self.version_file, self.revision, "stable",
            "6.11.1", self.signer, self.public_digest(), self.observation,
        )

    def activate_target_for_test(self) -> None:
        value = {
            "schemaVersion": 1,
            "channel": "stable",
            "releaseId": self.target_release.name,
            "manifestSequence": 42,
            "version": self.version,
            "sourceRevision": self.revision,
            "activatedAt": self.now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        }
        atomic_write(self.store / "active-channel.json",
                     json.dumps(value, sort_keys=True) + "\n")

    def test_derives_exact_reverse_transition_and_consumes_once(self) -> None:
        value = self.run_rollback()
        self.assertEqual(value["status"],
                         "rollback-pointer-restored-awaiting-external-observation")
        self.assertEqual(inspect_active(self.store, self.rollback_now)["releaseId"],
                         value["restoredReleaseId"])
        self.assertEqual(self.verify_rollback_file(), value)
        self.activate_target_for_test()
        with self.assertRaisesRegex(ManifestError, "already exists"):
            self.run_rollback(self.root / "second-rollback.json")

    def test_does_not_reactivate_failed_release_when_evidence_write_fails(self) -> None:
        self.prepare_rollback()
        self.rollback_evidence.write_text("occupied", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "already exists"):
            self.run_rollback()
        active = inspect_active(self.store, self.rollback_now)
        self.assertEqual(active["releaseId"], self.rollback_release.name)

    def test_rejects_wrong_active_release_before_consumption(self) -> None:
        self.prepare_rollback()
        pointer = json.loads((self.store / "active-channel.json").read_text(encoding="utf-8"))
        pointer["manifestSequence"] = 41
        atomic_write(self.store / "active-channel.json",
                     json.dumps(pointer, sort_keys=True) + "\n")
        with self.assertRaises(ManifestError):
            self.run_rollback()
        self.assertFalse((self.store / ".rollback-consumptions").exists())

    def test_rejects_rollback_after_prior_manifest_expiry(self) -> None:
        self.prepare_rollback()
        with self.assertRaisesRegex(ManifestError, "not currently valid"):
            execute_rollback(
                self.completion, self.execution, self.authorization,
                self.target_release, self.rollback_release, self.current_manifest,
                self.version_file, self.revision, "stable", "6.11.1", self.signer,
                self.public_digest(), self.observation, self.store,
                self.rollback_evidence, self.now + timedelta(days=8),
            )
        self.assertFalse((self.store / ".rollback-consumptions").exists())

    def test_rejects_mutated_or_duplicate_rollback_evidence(self) -> None:
        self.run_rollback()
        value = json.loads(self.rollback_evidence.read_text(encoding="utf-8"))
        value["restoredReleaseId"] = value["failedReleaseId"]
        self.rollback_evidence.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "does not match"):
            self.verify_rollback_file()
        self.rollback_evidence.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            self.verify_rollback_file()


if __name__ == "__main__":
    import unittest
    unittest.main()
