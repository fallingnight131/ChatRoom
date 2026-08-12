#!/usr/bin/env python3

from __future__ import annotations

import json
import os
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError  # noqa: E402
from web_promotion_evidence_test import WebPromotionEvidenceTest  # noqa: E402
from web_promotion_evidence import write_promotion_once  # noqa: E402
from web_release_authorization import create_authorization, write_once  # noqa: E402
from web_release_execution import execute, verify_execution  # noqa: E402
from web_release_store import activate_release, inspect_active_release, stage_release  # noqa: E402


class WebReleaseExecutionTest(WebPromotionEvidenceTest):
    def setUp(self) -> None:
        super().setUp()
        self.store = self.root / "store"
        current_id = stage_release(self.current, self.store)["releaseId"]
        rollback_id = stage_release(self.rollback, self.store)["releaseId"]
        self.staged_current = self.store / "releases" / current_id
        self.staged_rollback = self.store / "releases" / rollback_id
        activate_release(self.store, rollback_id, "2026-08-12T02:00:00Z")
        self.promotion = self.root / "execution-promotion.json"
        write_promotion_once(self.promotion, self._build())
        self.authorization = self.root / "execution-authorization.json"
        write_once(self.authorization, create_authorization(
            self.promotion, self.staged_current, self.current_observation,
            self.route_observation, self.staged_rollback,
            self.rollback_observation, self.now,
        ))
        self.execution = self.root / "execution.json"

    def run_execution(self, output=None):
        return execute(
            self.authorization, self.promotion, self.staged_current,
            self.current_observation, self.route_observation,
            self.staged_rollback, self.rollback_observation, self.store,
            output or self.execution, self.now,
        )

    def test_consumes_once_switches_pointer_and_writes_unpublished_evidence(self) -> None:
        result = self.run_execution()
        self.assertEqual(result["status"], "pointer-switched-awaiting-external-observation")
        self.assertEqual(inspect_active_release(self.store)["releaseId"], result["releaseId"])
        self.assertEqual(verify_execution(
            self.execution, self.authorization, self.promotion,
            self.staged_current, self.current_observation, self.route_observation,
            self.staged_rollback, self.rollback_observation,
        ), result)
        activate_release(self.store, result["rollbackReleaseId"], "2026-08-12T02:11:00Z")
        with self.assertRaisesRegex(ManifestError, "already exists"):
            self.run_execution(self.root / "second-execution.json")

    def test_rolls_pointer_back_when_evidence_commit_fails(self) -> None:
        self.execution.write_text("occupied", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "already exists"):
            self.run_execution()
        active = inspect_active_release(self.store)
        self.assertEqual(active["releaseId"], self.staged_rollback.name)
        self.assertFalse((self.store / "active-release.json").is_symlink())

    def test_rejects_wrong_active_pointer_before_consumption(self) -> None:
        activate_release(self.store, self.staged_current.name, "2026-08-12T02:09:00Z")
        with self.assertRaisesRegex(ManifestError, "not the authorized rollback target"):
            self.run_execution()
        consumption = self.store / ".promotion-consumptions"
        self.assertFalse(consumption.exists())

    @unittest.skipIf(os.name == "nt", "Windows symlink creation requires optional privileges")
    def test_rejects_symlinked_consumption_boundary(self) -> None:
        outside = self.root / "outside"
        outside.mkdir()
        (self.store / ".promotion-consumptions").symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ManifestError, "directory is unsafe"):
            self.run_execution()
        self.assertEqual(inspect_active_release(self.store)["releaseId"],
                         self.staged_rollback.name)

    def test_rejects_mutated_execution_evidence(self) -> None:
        self.run_execution()
        value = json.loads(self.execution.read_text(encoding="utf-8"))
        value["status"] = "published"
        self.execution.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "does not match authorization"):
            verify_execution(
                self.execution, self.authorization, self.promotion,
                self.staged_current, self.current_observation,
                self.route_observation, self.staged_rollback,
                self.rollback_observation,
            )

        self.execution.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            verify_execution(
                self.execution, self.authorization, self.promotion,
                self.staged_current, self.current_observation,
                self.route_observation, self.staged_rollback,
                self.rollback_observation,
            )


if __name__ == "__main__":
    unittest.main()
