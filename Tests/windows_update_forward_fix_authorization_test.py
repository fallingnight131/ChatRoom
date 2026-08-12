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
from artifact_manifest_common import ManifestError, atomic_write  # noqa: E402
from windows_update_channel_candidate import assemble_candidate  # noqa: E402
import windows_update_channel_candidate_test as channel_fixture  # noqa: E402
from windows_update_forward_fix_authorization import (  # noqa: E402
    create_authorization, verify_authorization, write_once,
)
from windows_update_manifest import canonical_bytes, sign_manifest  # noqa: E402
from windows_update_rollback_completion import write_once as write_completion  # noqa: E402
import windows_update_rollback_completion_test as rollback_fixture  # noqa: E402


class WindowsUpdateForwardFixAuthorizationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = rollback_fixture.WindowsUpdateRollbackCompletionTest(
            methodName="test_binds_restored_https_observation_to_rollout_halt_once")
        self.fixture.setUp()
        self.fixture.prepare_restored_observation()
        write_completion(
            self.fixture.rollback_completion,
            self.fixture.build_rollback_completion(),
        )
        self.forward_now = self.now + timedelta(minutes=6)
        self.forward_authorization = self.root / "forward-fix-authorization.json"
        self.targets: list[channel_fixture.WindowsUpdateChannelCandidateTest] = []

    def tearDown(self) -> None:
        for target in self.targets:
            target.tearDown()
        self.fixture.tearDown()

    @property
    def now(self):
        return self.fixture.now

    @property
    def root(self):
        return self.fixture.root

    def build_target(
        self,
        *,
        rollout_percentage: int = 100,
        sequence: int = 43,
        minimum_version: str = "1.2.3",
        share_failed_client_key: bool = True,
        candidate_type=None,
    ) -> channel_fixture.WindowsUpdateChannelCandidateTest:
        if candidate_type is None:
            class Candidate(channel_fixture.WindowsUpdateChannelCandidateTest):
                release_version = "1.2.4"
                source_revision = "f" * 40

            candidate_type = Candidate
        target = candidate_type(methodName="test_assembles_self_contained_signed_unpublished_candidate")
        if share_failed_client_key:
            target.provided_product_trust_private = self.fixture.private_key
            target.provided_product_trust_public = self.fixture.public_key
        target.setUp()
        self.targets.append(target)
        target.prepare_update_inputs()
        manifest = json.loads(target.update_manifest.read_text(encoding="utf-8"))
        manifest["manifestSequence"] = sequence
        manifest["minimumUpdatableVersion"] = minimum_version
        manifest["rollout"]["percentage"] = rollout_percentage
        target.update_manifest.unlink()
        target.update_signature.unlink()
        atomic_write(
            target.update_manifest,
            canonical_bytes(manifest).decode("utf-8"),
        )
        sign_manifest(
            target.update_manifest, target.private_key, target.update_signature)
        assemble_candidate(
            target.candidate, target.update_manifest, target.update_signature,
            target.public_key, target.update_candidate, target.version_file,
            target.revision, "stable", "6.11.1", target.signer,
            target.public_digest(), target.now,
        )
        return target

    def authorization_inputs(self, target):
        return (
            self.fixture.rollback_completion, self.fixture.inputs(),
            target.update_candidate,
            target.version_file, target.revision, "6.11.1", target.signer,
            target.public_digest(),
        )

    def test_authorizes_fully_compatible_higher_version_once(self) -> None:
        target = self.build_target()
        value = create_authorization(
            *self.authorization_inputs(target), self.forward_now)
        write_once(self.forward_authorization, value)
        self.assertEqual(value["status"], "forward-fix-approved-not-executed")
        self.assertEqual(value["failedVersion"], "1.2.3")
        self.assertEqual(value["targetVersion"], "1.2.4")
        self.assertEqual(value["targetManifestSequence"], 43)
        self.assertEqual(value["targetRolloutPercentage"], 100)
        self.assertEqual(
            verify_authorization(
                self.forward_authorization, *self.authorization_inputs(target),
                self.forward_now + timedelta(minutes=1)),
            value,
        )
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.forward_authorization, value)

    def test_rejects_partial_stale_or_client_excluding_target(self) -> None:
        for changes, message in (
            ({"rollout_percentage": 25}, "does not repair"),
            ({"sequence": 42}, "does not repair"),
            ({"minimum_version": "1.2.4"}, "does not repair"),
        ):
            with self.subTest(changes=changes):
                target = self.build_target(**changes)
                with self.assertRaisesRegex(ManifestError, message):
                    create_authorization(
                        *self.authorization_inputs(target), self.forward_now)

    def test_rejects_target_key_not_trusted_by_failed_client(self) -> None:
        target = self.build_target(share_failed_client_key=False)
        with self.assertRaisesRegex(ManifestError, "not compiled into the client"):
            create_authorization(
                *self.authorization_inputs(target), self.forward_now)

    def test_rejects_same_version_even_with_new_source_and_sequence(self) -> None:
        class SameVersionCandidate(channel_fixture.WindowsUpdateChannelCandidateTest):
            release_version = "1.2.3"
            source_revision = "f" * 40

        target = self.build_target(candidate_type=SameVersionCandidate)
        with self.assertRaisesRegex(ManifestError, "does not repair"):
            create_authorization(
                *self.authorization_inputs(target), self.forward_now)

    def test_rejects_expired_or_mutated_authorization(self) -> None:
        target = self.build_target()
        value = create_authorization(
            *self.authorization_inputs(target), self.forward_now,
            lifetime_seconds=60)
        write_once(self.forward_authorization, value)
        with self.assertRaisesRegex(ManifestError, "expired"):
            verify_authorization(
                self.forward_authorization, *self.authorization_inputs(target),
                self.forward_now + timedelta(seconds=60))
        value["targetManifestSequence"] = 44
        self.forward_authorization.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "differs"):
            verify_authorization(
                self.forward_authorization, *self.authorization_inputs(target),
                self.forward_now + timedelta(seconds=30))

    def test_authorization_contains_no_private_material_or_side_effect_marker(self) -> None:
        target = self.build_target()
        value = create_authorization(
            *self.authorization_inputs(target), self.forward_now)
        serialized = json.dumps(value, sort_keys=True).lower()
        self.assertNotIn("private", serialized)
        self.assertNotIn("published", serialized)
        self.assertFalse((self.root / ".forward-fix-consumptions").exists())


if __name__ == "__main__":
    unittest.main()
