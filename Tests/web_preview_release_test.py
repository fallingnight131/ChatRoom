#!/usr/bin/env python3

from __future__ import annotations

import json
import os
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError  # noqa: E402
import web_promotion_evidence_test as release_fixture  # noqa: E402
from web_preview_release import inspect_preview, select_preview  # noqa: E402
from web_release_store import (  # noqa: E402
    activate_release, inspect_active_release, stage_release,
)


class WebPreviewReleaseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.base = release_fixture.WebPromotionEvidenceTest(
            methodName="test_binds_fresh_release_routes_and_distinct_retained_rollback")
        self.base.setUp()
        self.store = self.base.root / "preview-store"
        self.current_id = stage_release(self.base.current, self.store)["releaseId"]
        self.rollback_id = stage_release(self.base.rollback, self.store)["releaseId"]
        activate_release(self.store, self.rollback_id, "2026-08-12T02:00:00Z")
        self.now = datetime(2026, 8, 12, 2, 8, 0, tzinfo=timezone.utc)

    def tearDown(self) -> None:
        self.base.tearDown()

    def test_selects_candidate_without_changing_production_pointer(self) -> None:
        production_before = inspect_active_release(self.store)
        value = select_preview(self.store, self.current_id, self.now)
        self.assertEqual(value["purpose"], "non-production-candidate-preview")
        self.assertEqual(inspect_preview(self.store, self.now)["releaseId"],
                         self.current_id)
        self.assertEqual(inspect_active_release(self.store), production_before)

    def test_rejects_tampered_or_future_preview_pointer(self) -> None:
        select_preview(self.store, self.current_id, self.now)
        pointer = self.store / "preview-release.json"
        value = json.loads(pointer.read_text(encoding="utf-8"))
        value["releaseId"] = self.rollback_id
        pointer.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "differs"):
            inspect_preview(self.store, self.now)
        value["releaseId"] = self.current_id
        value["selectedAt"] = "2026-08-12T02:09:00Z"
        pointer.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "future"):
            inspect_preview(self.store, self.now)

    @unittest.skipIf(os.name == "nt", "Windows symlink creation needs privileges")
    def test_rejects_symlinked_preview_boundary(self) -> None:
        outside = self.base.root / "outside.json"
        outside.write_text("{}", encoding="utf-8")
        (self.store / "preview-release.json").symlink_to(outside)
        with self.assertRaisesRegex(ManifestError, "unsafe"):
            select_preview(self.store, self.current_id, self.now)


if __name__ == "__main__":
    unittest.main()
