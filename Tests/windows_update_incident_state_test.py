#!/usr/bin/env python3

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError  # noqa: E402
from windows_update_incident_state import (  # noqa: E402
    inspect_open_incident, open_incident, require_no_open_incident,
)


class WindowsUpdateIncidentStateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.store = self.root / "store"
        self.store.mkdir()
        self.completion = self.root / "promotion-completion.json"
        self.completion.write_text('{"closed":true}\n', encoding="utf-8")
        self.now = datetime(2026, 8, 13, 0, 0, 0, tzinfo=timezone.utc)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def open(self):
        return open_incident(
            self.store, self.completion, "stable", "a" * 64, "b" * 64,
            self.now)

    def test_retains_exact_open_incident_and_blocks_general_execution(self) -> None:
        value = self.open()
        self.assertEqual(inspect_open_incident(self.store, self.now), value)
        self.assertEqual(value["promotionCompletionSha256"], value["incidentId"])
        with self.assertRaisesRegex(ManifestError, "dedicated forward-fix"):
            require_no_open_incident(self.store, "stable", self.now)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            self.open()

    def test_no_marker_allows_general_execution(self) -> None:
        self.assertIsNone(inspect_open_incident(self.store, self.now))
        self.assertIsNone(require_no_open_incident(self.store, "stable", self.now))

    def test_rejects_active_marker_tamper_and_missing_retained_record(self) -> None:
        value = self.open()
        active = self.store / ".open-rollout-incident.json"
        active.unlink()
        active.write_text(json.dumps({**value, "failedReleaseId": "c" * 64}),
                          encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "differs"):
            inspect_open_incident(self.store, self.now)
        active.unlink()
        retained = self.store / ".rollout-incidents" / f"{value['incidentId']}.json"
        retained.unlink()
        active.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "regular file"):
            inspect_open_incident(self.store, self.now)

    @unittest.skipIf(os.name == "nt", "Windows symlink needs privileges")
    def test_rejects_symlinked_active_marker(self) -> None:
        target = self.root / "outside.json"
        target.write_text("{}", encoding="utf-8")
        (self.store / ".open-rollout-incident.json").symlink_to(target)
        with self.assertRaisesRegex(ManifestError, "regular file"):
            inspect_open_incident(self.store, self.now)


if __name__ == "__main__":
    unittest.main()
