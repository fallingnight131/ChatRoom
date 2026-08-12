#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError  # noqa: E402
from web_staged_release_completion import (  # noqa: E402
    build_completion, verify_staged_completion, write_once,
)


class WebStagedReleaseCompletionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.evidence = self.root / "evidence"
        self.evidence.mkdir()
        self.release = self.root / "release"
        self.rollback = self.root / "rollback"
        self.release.mkdir()
        self.rollback.mkdir()
        names = [
            "candidate-preview-health-reviewed.json", "production-health.json",
            "production-completion.json", "pointer-execution.json",
            "production-authorization.json", "technical-promotion-reviewed.json",
            "rollback-static-reviewed.json",
        ]
        names += [f"candidate-static-reviewed{suffix}.json" for suffix in ("", "-1", "-2")]
        names += [f"candidate-routes-reviewed{suffix}.json" for suffix in ("", "-1", "-2")]
        names += [f"post-static{suffix}.json" for suffix in ("", "-1", "-2")]
        names += [f"post-routes{suffix}.json" for suffix in ("", "-1", "-2")]
        for index, name in enumerate(names):
            (self.evidence / name).write_text(json.dumps({"fixture": index}), encoding="utf-8")
        (self.evidence / "pointer-execution.json").write_text(json.dumps({
            "executedAt": "2026-08-13T12:10:00Z",
        }), encoding="utf-8")
        identity = {
            "releaseId": "1.2.3-" + "a" * 40,
            "version": "1.2.3",
            "sourceRevision": "a" * 40,
        }
        self.preview = {
            **identity, "baseUrl": "https://preview.example.test",
            "endedAt": "2026-08-13T12:09:30Z",
        }
        self.production = {
            **identity, "baseUrl": "https://chat.example.test",
            "startedAt": "2026-08-13T12:10:05Z",
            "endedAt": "2026-08-13T12:11:05Z",
        }
        self.completion = {
            **identity, "baseUrl": "https://chat.example.test",
            "rollbackReleaseId": "1.2.2-" + "b" * 40,
            "completedAt": "2026-08-13T12:11:10Z",
        }
        self.now = datetime(2026, 8, 13, 12, 11, 20, tzinfo=timezone.utc)
        self.output = self.root / "staged-completion.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def build(self, preview=None, production=None, completion=None):
        with patch("web_staged_release_completion.verify_window",
                   side_effect=[preview or self.preview, production or self.production]), \
             patch("web_staged_release_completion.verify_completion",
                   return_value=completion or self.completion):
            return build_completion(
                self.evidence, self.release, self.rollback, self.now)

    def test_binds_preview_execution_production_and_completion_once(self) -> None:
        value = self.build()
        write_once(self.output, value)
        self.assertEqual(
            value["status"], "preview-production-health-and-promotion-observed")
        with patch("web_staged_release_completion.verify_window",
                   side_effect=[self.preview, self.production]), \
             patch("web_staged_release_completion.verify_completion",
                   return_value=self.completion):
            self.assertEqual(verify_staged_completion(
                self.output, self.evidence, self.release, self.rollback), value)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.output, value)

    def test_rejects_mixed_identity_origin_and_invalid_order(self) -> None:
        changed = deepcopy(self.production)
        changed["releaseId"] = "9.9.9-" + "c" * 40
        with self.assertRaisesRegex(ManifestError, "identities or order"):
            self.build(production=changed)
        changed = deepcopy(self.preview)
        changed["baseUrl"] = self.production["baseUrl"]
        with self.assertRaisesRegex(ManifestError, "identities or order"):
            self.build(preview=changed)
        changed = deepcopy(self.production)
        changed["startedAt"] = "2026-08-13T12:09:59Z"
        with self.assertRaisesRegex(ManifestError, "identities or order"):
            self.build(production=changed)

    def test_rejects_mutated_or_unknown_completion(self) -> None:
        value = self.build()
        value["productionHealthSha256"] = "f" * 64
        self.output.write_text(json.dumps(value), encoding="utf-8")
        with patch("web_staged_release_completion.verify_window",
                   side_effect=[self.preview, self.production]), \
             patch("web_staged_release_completion.verify_completion",
                   return_value=self.completion), \
             self.assertRaisesRegex(ManifestError, "differs"):
            verify_staged_completion(
                self.output, self.evidence, self.release, self.rollback)
        value["unknown"] = True
        self.output.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "shape"):
            verify_staged_completion(
                self.output, self.evidence, self.release, self.rollback)
        self.output.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate"):
            verify_staged_completion(
                self.output, self.evidence, self.release, self.rollback)


if __name__ == "__main__":
    unittest.main()
