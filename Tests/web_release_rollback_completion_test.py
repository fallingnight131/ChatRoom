#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import unittest
from copy import deepcopy
from datetime import timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError  # noqa: E402
import web_release_rollback_execution_test as rollback_fixture  # noqa: E402
from web_release_rollback_completion import (  # noqa: E402
    build_completion, verify_completion, write_once,
)


class WebReleaseRollbackCompletionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.base = rollback_fixture.WebReleaseRollbackExecutionTest(
            methodName="test_restores_exact_prior_pointer_once_and_writes_pending_evidence")
        self.base.setUp()
        self.base.run_rollback()
        self.restored_release = self.base.root / "restored-release.json"
        release = json.loads(self.base.rollback_observation.read_text(encoding="utf-8"))
        release["observedAt"] = "2026-08-12T02:14:00+00:00"
        self.restored_release.write_text(json.dumps(release), encoding="utf-8")
        self.restored_routes = self.base.root / "restored-routes.json"
        routes = json.loads(self.base.route_observation.read_text(encoding="utf-8"))
        routes["baseUrl"] = "https://chat.example.test"
        routes["observedAt"] = "2026-08-12T02:14:30+00:00"
        self.restored_routes.write_text(json.dumps(routes), encoding="utf-8")
        self.now = self.base.now + timedelta(minutes=5)
        self.output = self.base.root / "rollback-completion.json"

    def tearDown(self) -> None:
        self.base.tearDown()

    def inputs(self):
        return (
            self.base.rollback_execution, self.base.execution,
            self.base.authorization, self.base.promotion,
            self.base.staged_current, self.base.current_observation,
            self.base.route_observation, self.base.staged_rollback,
            self.base.rollback_observation, self.restored_release,
            self.restored_routes,
        )

    def test_binds_restored_static_routes_and_rollback_execution_once(self) -> None:
        value = build_completion(*self.inputs(), self.now)
        write_once(self.output, value)
        self.assertEqual(value["status"], "production-rollback-observed")
        self.assertEqual(verify_completion(self.output, *self.inputs()), value)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.output, value)

    def test_rejects_pre_rollback_late_split_or_wrong_release_observation(self) -> None:
        cases = (
            (self.restored_release, "observedAt", "2026-08-12T02:12:59+00:00"),
            (self.restored_release, "observedAt", "2026-08-12T02:24:00+00:00"),
            (self.restored_routes, "observedAt", "2026-08-12T02:20:00+00:00"),
            (self.restored_release, "releaseId", "9.9.9-" + "f" * 40),
        )
        for path, key, changed_value in cases:
            with self.subTest(key=key, value=changed_value):
                original = json.loads(path.read_text(encoding="utf-8"))
                changed = deepcopy(original)
                changed[key] = changed_value
                path.write_text(json.dumps(changed), encoding="utf-8")
                with self.assertRaises(ManifestError):
                    build_completion(
                        *self.inputs(), self.base.now + timedelta(minutes=9))
                path.write_text(json.dumps(original), encoding="utf-8")

    def test_rejects_mutated_duplicate_and_invalid_window(self) -> None:
        value = build_completion(*self.inputs(), self.now)
        self.output.write_text(json.dumps({**value, "failedReleaseId": "x"}),
                               encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "differs"):
            verify_completion(self.output, *self.inputs())
        self.output.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate"):
            verify_completion(self.output, *self.inputs())
        with self.assertRaisesRegex(ManifestError, "60 to 900"):
            build_completion(*self.inputs(), self.now, 59)


if __name__ == "__main__":
    unittest.main()
