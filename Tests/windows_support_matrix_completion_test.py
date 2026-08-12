#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError  # noqa: E402
import windows_support_host_evidence_test as host_fixture  # noqa: E402
from windows_support_matrix_completion import (  # noqa: E402
    build_completion, verify_completion, write_once,
)


class WindowsSupportMatrixCompletionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.base = host_fixture.WindowsSupportHostEvidenceTest(
            methodName="test_accepts_exact_closed_supported_host_transition")
        self.base.setUp()
        self.paths = {}
        values = (
            ("windows-10-22h2", "Microsoft Windows 10 Enterprise", "10.0.19045", 19045),
            ("windows-11-23h2", "Microsoft Windows 11 Enterprise", "10.0.22631", 22631),
            ("windows-11-24h2", "Microsoft Windows 11 Enterprise", "10.0.26100", 26100),
        )
        for target, caption, version, build in values:
            path = self.base.current.root / f"{target}.json"
            evidence = deepcopy(self.base.evidence)
            evidence.update({"targetId": target, "osCaption": caption,
                             "osVersion": version, "osBuild": build})
            path.write_text(json.dumps(evidence), encoding="utf-8")
            self.paths[target] = path
        self.output = self.base.current.root / "matrix-completion.json"

    def tearDown(self) -> None:
        self.base.tearDown()

    def inputs(self):
        return (
            self.paths, self.base.policy, self.base.current.candidate,
            self.base.current.version_file, self.base.current.revision,
            self.base.previous.candidate, self.base.previous.version_file,
            self.base.previous.revision, "stable", "6.11.1",
            self.base.current.signer,
        )

    def test_closes_all_exact_targets_once(self) -> None:
        value = build_completion(*self.inputs(), self.base.current.now)
        write_once(self.output, value)
        self.assertEqual(
            value["status"], "all-supported-windows-client-targets-observed")
        self.assertEqual([item["targetId"] for item in value["targets"]],
                         list(self.paths))
        self.assertEqual(verify_completion(self.output, *self.inputs()), value)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.output, value)

    def test_rejects_missing_target_or_mixed_host_identity(self) -> None:
        missing = dict(self.paths)
        missing.pop("windows-10-22h2")
        with self.assertRaisesRegex(ManifestError, "incomplete"):
            build_completion(
                missing, *self.inputs()[1:], self.base.current.now)
        value = json.loads(self.paths["windows-10-22h2"].read_text(encoding="utf-8"))
        value["currentSourceRevision"] = "e" * 40
        self.paths["windows-10-22h2"].write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaises(ManifestError):
            build_completion(*self.inputs(), self.base.current.now)

    def test_rejects_mutated_or_duplicate_completion(self) -> None:
        value = build_completion(*self.inputs(), self.base.current.now)
        changed = deepcopy(value)
        changed["targets"][0]["evidenceSha256"] = "f" * 64
        self.output.write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "differs"):
            verify_completion(self.output, *self.inputs())
        self.output.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate"):
            verify_completion(self.output, *self.inputs())


if __name__ == "__main__":
    unittest.main()
