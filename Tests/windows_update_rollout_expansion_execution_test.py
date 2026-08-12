#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError, atomic_write  # noqa: E402
from windows_update_channel_store import stage_release  # noqa: E402
from windows_update_release_execution import inspect_active  # noqa: E402
from windows_update_incident_state import open_incident  # noqa: E402
import windows_update_rollout_expansion_authorization_test as authorization_fixture  # noqa: E402
from windows_update_rollout_expansion_authorization import (  # noqa: E402
    create_authorization, write_once as write_authorization,
)
from windows_update_rollout_expansion_execution import (  # noqa: E402
    execute, verify_execution,
)


class WindowsUpdateRolloutExpansionExecutionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.base = authorization_fixture.WindowsUpdateRolloutExpansionAuthorizationTest(
            methodName="test_authorizes_only_attested_next_percentage_once")
        self.base.setUp()
        write_authorization(
            self.base.authorization,
            create_authorization(*self.base.values(), self.base.now))
        fixture = self.base.fixture
        staged = stage_release(
            self.base.target, fixture.store, fixture.version_file,
            fixture.revision, "stable", "6.11.1", fixture.signer,
            fixture.public_digest(), self.base.now)
        self.target_release = (
            fixture.store / "releases" / str(staged["releaseId"]))
        self.values = self.base.values(self.target_release)
        self.evidence = self.base.root / "expansion-execution.json"

    def tearDown(self) -> None:
        self.base.tearDown()

    def test_consumes_once_and_switches_exact_staged_percentage(self) -> None:
        pointer_path = self.base.fixture.store / "active-channel.json"
        previous_pointer = pointer_path.read_bytes()
        result = execute(
            self.base.authorization, self.values, self.base.fixture.store,
            self.evidence, self.base.now)
        self.assertEqual(
            result["status"],
            "rollout-expansion-pointer-switched-awaiting-external-observation")
        self.assertEqual(result["currentRolloutPercentage"], 10)
        self.assertEqual(result["targetRolloutPercentage"], 25)
        self.assertEqual(
            inspect_active(self.base.fixture.store, self.base.now)["releaseId"],
            result["releaseId"])
        self.assertEqual(
            verify_execution(self.evidence, self.base.authorization, self.values),
            result)
        pointer_path.write_bytes(previous_pointer)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            execute(
                self.base.authorization, self.values, self.base.fixture.store,
                self.base.root / "retry.json", self.base.now)

    def test_rejects_stale_active_pointer_before_consumption(self) -> None:
        fixture = self.base.fixture
        rollback = fixture.rollback_release
        identity = json.loads((
            rollback / "windows-update-channel-candidate.json").read_text(
                encoding="utf-8"))
        manifest = json.loads((rollback / "update/manifest.json").read_text(
            encoding="utf-8"))
        atomic_write(fixture.store / "active-channel.json", json.dumps({
            "schemaVersion": 1,
            "channel": "stable",
            "releaseId": rollback.name,
            "manifestSequence": manifest["manifestSequence"],
            "version": identity["version"],
            "sourceRevision": identity["sourceRevision"],
            "activatedAt": "2026-08-12T12:00:00Z",
        }, indent=2, sort_keys=True) + "\n")
        with self.assertRaisesRegex(ManifestError, "active pointer changed"):
            execute(
                self.base.authorization, self.values, fixture.store,
                self.evidence, self.base.now)
        consumptions = fixture.store / ".rollout-expansion-consumptions"
        self.assertFalse(consumptions.exists())

    def test_evidence_failure_restores_pointer_but_spends_authorization(self) -> None:
        self.evidence.write_text("occupied", encoding="utf-8")
        before = inspect_active(self.base.fixture.store, self.base.now)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            execute(
                self.base.authorization, self.values, self.base.fixture.store,
                self.evidence, self.base.now)
        after = inspect_active(self.base.fixture.store, self.base.now)
        self.assertEqual(after["releaseId"], before["releaseId"])
        with self.assertRaisesRegex(ManifestError, "already exists"):
            execute(
                self.base.authorization, self.values, self.base.fixture.store,
                self.base.root / "second.json", self.base.now)

    def test_rejects_execution_evidence_mutation(self) -> None:
        execute(
            self.base.authorization, self.values, self.base.fixture.store,
            self.evidence, self.base.now)
        value = json.loads(self.evidence.read_text(encoding="utf-8"))
        value["targetRolloutPercentage"] = 50
        self.evidence.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "differs"):
            verify_execution(self.evidence, self.base.authorization, self.values)

    def test_rejects_expansion_while_rollout_incident_is_open(self) -> None:
        fixture = self.base.fixture
        open_incident(
            fixture.store, self.base.authorization, "stable", "c" * 64,
            "d" * 64, self.base.now)
        with self.assertRaisesRegex(ManifestError, "dedicated forward-fix"):
            execute(
                self.base.authorization, self.values, fixture.store,
                self.evidence, self.base.now)
        self.assertFalse(
            (fixture.store / ".rollout-expansion-consumptions").exists())


if __name__ == "__main__":
    unittest.main()
