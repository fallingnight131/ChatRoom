#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import unittest
from datetime import timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError  # noqa: E402
from web_release_execution_test import WebReleaseExecutionTest  # noqa: E402
from web_release_rollback_execution import rollback, verify_rollback  # noqa: E402
from web_release_store import activate_release, inspect_active_release  # noqa: E402


class WebReleaseRollbackExecutionTest(WebReleaseExecutionTest):
    def setUp(self) -> None:
        super().setUp()
        self.rollback_execution = self.root / "rollback-execution.json"
        self.rollback_now = self.now + timedelta(minutes=3)

    def run_rollback(self, output=None):
        if not self.execution.exists():
            self.run_execution()
        return rollback(
            self.execution, self.authorization, self.promotion,
            self.staged_current, self.current_observation,
            self.route_observation, self.staged_rollback,
            self.rollback_observation, self.store,
            output or self.rollback_execution, self.rollback_now,
        )

    def verify_rollback_file(self):
        return verify_rollback(
            self.rollback_execution, self.execution, self.authorization,
            self.promotion, self.staged_current, self.current_observation,
            self.route_observation, self.staged_rollback,
            self.rollback_observation,
        )

    def test_restores_exact_prior_pointer_once_and_writes_pending_evidence(self) -> None:
        result = self.run_rollback()
        self.assertEqual(result["status"],
                         "rollback-pointer-restored-awaiting-external-observation")
        self.assertEqual(inspect_active_release(self.store)["releaseId"],
                         self.staged_rollback.name)
        self.assertEqual(self.verify_rollback_file(), result)
        activate_release(self.store, self.staged_current.name, "2026-08-12T02:14:00Z")
        with self.assertRaisesRegex(ManifestError, "already exists"):
            self.run_rollback(self.root / "second-rollback.json")

    def test_refuses_wrong_active_release_before_consumption(self) -> None:
        self.run_execution()
        activate_release(self.store, self.staged_rollback.name, "2026-08-12T02:12:00Z")
        with self.assertRaisesRegex(ManifestError, "not the failed release"):
            self.run_rollback()
        self.assertFalse((self.store / ".rollback-consumptions").exists())

    def test_evidence_failure_keeps_safer_rollback_active(self) -> None:
        self.run_execution()
        self.rollback_execution.write_text("occupied", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "already exists"):
            self.run_rollback()
        self.assertEqual(inspect_active_release(self.store)["releaseId"],
                         self.staged_rollback.name)

    def test_rejects_mutated_duplicate_and_backdated_evidence(self) -> None:
        value = self.run_rollback()
        changed = dict(value)
        changed["status"] = "rollback-complete"
        self.rollback_execution.write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "does not match"):
            self.verify_rollback_file()
        self.rollback_execution.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            self.verify_rollback_file()
        changed = dict(value)
        changed["rollbackExecutedAt"] = "2026-08-12T02:09:00Z"
        self.rollback_execution.write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "precedes"):
            self.verify_rollback_file()


if __name__ == "__main__":
    unittest.main()
