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
from web_release_execution_test import WebReleaseExecutionTest  # noqa: E402
from web_release_completion import (  # noqa: E402
    build_completion, verify_completion, write_once,
)


class WebReleaseCompletionTest(WebReleaseExecutionTest):
    def setUp(self) -> None:
        super().setUp()
        self.post_release = self.root / "post-release.json"
        release = json.loads(self.current_observation.read_text(encoding="utf-8"))
        release["baseUrl"] = "https://chat.example.test"
        release["observedAt"] = "2026-08-12T02:11:00+00:00"
        self.post_release.write_text(json.dumps(release), encoding="utf-8")
        self.post_routes = self.root / "post-routes.json"
        routes = json.loads(self.route_observation.read_text(encoding="utf-8"))
        routes["baseUrl"] = "https://chat.example.test"
        routes["observedAt"] = "2026-08-12T02:11:30+00:00"
        self.post_routes.write_text(json.dumps(routes), encoding="utf-8")
        self.completed_at = self.now + timedelta(minutes=2)
        self.completion = self.root / "completion.json"

    def build(self, now=None, maximum=600):
        if not self.execution.exists():
            self.run_execution()
        return build_completion(
            self.execution, self.authorization, self.promotion,
            self.staged_current, self.current_observation,
            self.route_observation, self.staged_rollback,
            self.rollback_observation, self.post_release, self.post_routes,
            now or self.completed_at, maximum,
        )

    def verify_completion_file(self):
        return verify_completion(
            self.completion, self.execution, self.authorization, self.promotion,
            self.staged_current, self.current_observation,
            self.route_observation, self.staged_rollback,
            self.rollback_observation, self.post_release, self.post_routes,
        )

    def test_binds_post_switch_static_and_routes_into_completion(self) -> None:
        evidence = self.build()
        write_once(self.completion, evidence)
        self.assertEqual(evidence["status"], "production-promotion-observed")
        self.assertEqual(self.verify_completion_file(), evidence)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.completion, evidence)

    def test_rejects_pre_switch_late_and_split_observations(self) -> None:
        for path, time_value, message in (
            (self.post_release, "2026-08-12T02:09:59+00:00", "completion window"),
            (self.post_release, "2026-08-12T02:21:00+00:00", "completion window"),
            (self.post_routes, "2026-08-12T02:19:00+00:00", "completion window"),
        ):
            original = json.loads(path.read_text(encoding="utf-8"))
            changed = deepcopy(original)
            changed["observedAt"] = time_value
            path.write_text(json.dumps(changed), encoding="utf-8")
            with self.assertRaisesRegex(ManifestError, message):
                self.build(now=self.now + timedelta(minutes=9))
            path.write_text(json.dumps(original), encoding="utf-8")

    def test_rejects_identity_and_source_mutation(self) -> None:
        evidence = self.build()
        write_once(self.completion, evidence)
        release = json.loads(self.post_release.read_text(encoding="utf-8"))
        release["releaseId"] = release["releaseId"].replace("1.1.0", "9.9.9")
        self.post_release.write_text(json.dumps(release), encoding="utf-8")
        with self.assertRaises(ManifestError):
            self.verify_completion_file()

    def test_rejects_unknown_duplicate_and_invalid_window(self) -> None:
        value = self.build()
        self.completion.write_text(json.dumps({**value, "unknown": True}), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "unsupported shape"):
            self.verify_completion_file()
        self.completion.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            self.verify_completion_file()
        with self.assertRaisesRegex(ManifestError, "60 to 900"):
            self.build(maximum=59)


if __name__ == "__main__":
    unittest.main()
