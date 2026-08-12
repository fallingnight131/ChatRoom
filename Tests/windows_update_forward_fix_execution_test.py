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
from windows_update_channel_store import stage_release  # noqa: E402
import windows_update_forward_fix_authorization_test as authorization_fixture  # noqa: E402
from windows_update_forward_fix_authorization import (  # noqa: E402
    create_authorization, write_once,
)
from windows_update_forward_fix_execution import (  # noqa: E402
    execute, verify_execution,
)
from windows_update_incident_state import inspect_open_incident  # noqa: E402
from windows_update_release_execution import inspect_active  # noqa: E402


class WindowsUpdateForwardFixExecutionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.base = authorization_fixture.WindowsUpdateForwardFixAuthorizationTest(
            methodName="test_authorizes_fully_compatible_higher_version_once")
        self.base.setUp()
        self.target = self.base.build_target()
        initial_values = self.base.authorization_inputs(self.target)
        write_once(
            self.base.forward_authorization,
            create_authorization(*initial_values, self.base.forward_now),
        )
        fixture = self.base.fixture
        staged = stage_release(
            self.target.update_candidate, fixture.store,
            self.target.version_file, self.target.revision, "stable", "6.11.1",
            self.target.signer, self.target.public_digest(), self.base.forward_now)
        self.target_release = fixture.store / "releases" / str(staged["releaseId"])
        self.values = (
            self.base.fixture.rollback_completion, self.base.fixture.inputs(),
            self.target_release, self.target.version_file, self.target.revision,
            "6.11.1", self.target.signer, self.target.public_digest(),
        )
        self.now = self.base.forward_now + timedelta(minutes=1)
        self.evidence = self.base.root / "forward-fix-execution.json"

    def tearDown(self) -> None:
        self.base.tearDown()

    def test_consumes_once_and_switches_exact_forward_fix(self) -> None:
        fixture = self.base.fixture
        incident_before = inspect_open_incident(fixture.store, self.now)
        result = execute(
            self.base.forward_authorization, self.values, fixture.store,
            self.evidence, self.now)
        self.assertEqual(
            result["status"],
            "forward-fix-pointer-switched-awaiting-external-observation")
        self.assertEqual(result["incidentId"], incident_before["incidentId"])
        self.assertEqual(
            inspect_active(fixture.store, self.now)["releaseId"],
            result["releaseId"])
        self.assertEqual(
            verify_execution(
                self.evidence, self.base.forward_authorization, self.values),
            result)
        self.assertEqual(
            inspect_open_incident(fixture.store, self.now), incident_before)
        self.assertEqual(
            len(list((fixture.store / ".forward-fix-consumptions").glob("*.json"))),
            1)
        with self.assertRaises(ManifestError):
            execute(
                self.base.forward_authorization, self.values, fixture.store,
                self.base.root / "retry.json", self.now)

    def test_evidence_failure_restores_A_but_spends_authorization(self) -> None:
        fixture = self.base.fixture
        before = inspect_active(fixture.store, self.now)
        self.evidence.write_text("occupied", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "already exists"):
            execute(
                self.base.forward_authorization, self.values, fixture.store,
                self.evidence, self.now)
        self.assertEqual(
            inspect_active(fixture.store, self.now)["releaseId"],
            before["releaseId"])
        self.assertIsNotNone(inspect_open_incident(fixture.store, self.now))
        with self.assertRaisesRegex(ManifestError, "already exists"):
            execute(
                self.base.forward_authorization, self.values, fixture.store,
                self.base.root / "second.json", self.now)

    def test_rejects_missing_or_changed_open_incident_before_consumption(self) -> None:
        fixture = self.base.fixture
        active_incident = fixture.store / ".open-rollout-incident.json"
        original = json.loads(active_incident.read_text(encoding="utf-8"))
        active_incident.unlink()
        with self.assertRaisesRegex(ManifestError, "differs"):
            execute(
                self.base.forward_authorization, self.values, fixture.store,
                self.evidence, self.now)
        self.assertFalse((fixture.store / ".forward-fix-consumptions").exists())
        active_incident.write_text(json.dumps({
            **original, "failedReleaseId": "c" * 64,
        }), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "differs"):
            execute(
                self.base.forward_authorization, self.values, fixture.store,
                self.evidence, self.now)
        self.assertFalse((fixture.store / ".forward-fix-consumptions").exists())

    def test_rejects_wrong_active_release_before_consumption(self) -> None:
        fixture = self.base.fixture
        pointer = json.loads((fixture.store / "active-channel.json").read_text(
            encoding="utf-8"))
        pointer["manifestSequence"] += 1
        (fixture.store / "active-channel.json").write_text(
            json.dumps(pointer), encoding="utf-8")
        with self.assertRaises(ManifestError):
            execute(
                self.base.forward_authorization, self.values, fixture.store,
                self.evidence, self.now)
        self.assertFalse((fixture.store / ".forward-fix-consumptions").exists())

    def test_rejects_mutated_execution_evidence(self) -> None:
        execute(
            self.base.forward_authorization, self.values,
            self.base.fixture.store, self.evidence, self.now)
        value = json.loads(self.evidence.read_text(encoding="utf-8"))
        value["incidentId"] = "e" * 64
        self.evidence.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "differs"):
            verify_execution(
                self.evidence, self.base.forward_authorization, self.values)


if __name__ == "__main__":
    unittest.main()
