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
import web_browser_host_evidence_test as host_fixture  # noqa: E402
from web_browser_matrix_completion import (  # noqa: E402
    TARGETS, build_completion, verify_completion, write_once,
)


class WebBrowserMatrixCompletionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.base = host_fixture.WebBrowserHostEvidenceTest(
            methodName="test_accepts_exact_branded_browser_candidate_smoke")
        self.base.setUp()
        versions = {
            "chrome-current": "140.0.7339.80",
            "chrome-previous": "139.0.7258.155",
            "edge-current": "140.0.3485.54",
            "edge-previous": "139.0.3405.125",
            "firefox-current": "142.0",
            "firefox-previous": "141.0.3",
        }
        products = {
            "chrome": ("Google Chrome", "Chrome/"),
            "edge": ("Microsoft Edge", "Edg/"),
            "firefox": ("Mozilla Firefox", "Firefox/"),
        }
        self.paths = {}
        self.expectations = {}
        for index, target in enumerate(TARGETS):
            family, position = target.split("-")
            product, token = products[family]
            digest = format(index + 1, "x") * 64
            evidence = deepcopy(self.base.value)
            evidence.update({
                "targetId": target,
                "browserFamily": family,
                "browserProduct": product,
                "supportPosition": position,
                "browserVersion": versions[target],
                "browserExecutableSha256": digest,
                "userAgent": f"Mozilla/5.0 {token}{versions[target]}",
            })
            path = self.base.fixture.root / f"{target}.json"
            path.write_text(json.dumps(evidence), encoding="utf-8")
            self.paths[target] = path
            self.expectations[target] = (versions[target], digest)
        self.output = self.base.fixture.root / "browser-matrix.json"

    def tearDown(self) -> None:
        self.base.tearDown()

    def inputs(self):
        return self.paths, self.expectations, self.base.policy, self.base.release

    def test_closes_all_six_exact_targets_once(self) -> None:
        value = build_completion(*self.inputs(), self.base.now)
        write_once(self.output, value)
        self.assertEqual(value["status"], "all-six-branded-browser-targets-observed")
        self.assertEqual([item["targetId"] for item in value["targets"]], list(TARGETS))
        self.assertEqual(verify_completion(self.output, *self.inputs()), value)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.output, value)

    def test_rejects_missing_target_or_non_newer_current_version(self) -> None:
        missing = dict(self.paths)
        missing.pop("edge-previous")
        with self.assertRaisesRegex(ManifestError, "incomplete"):
            build_completion(
                missing, self.expectations, self.base.policy, self.base.release,
                self.base.now)
        changed = dict(self.expectations)
        changed["firefox-current"] = changed["firefox-previous"]
        with self.assertRaisesRegex(ManifestError, "newer"):
            build_completion(
                self.paths, changed, self.base.policy, self.base.release,
                self.base.now)

    def test_rejects_mixed_candidate_and_mutated_or_duplicate_completion(self) -> None:
        evidence = json.loads(self.paths["chrome-previous"].read_text(encoding="utf-8"))
        evidence["sourceRevision"] = "e" * 40
        self.paths["chrome-previous"].write_text(json.dumps(evidence), encoding="utf-8")
        with self.assertRaises(ManifestError):
            build_completion(*self.inputs(), self.base.now)
        self.setUp_after_mutation_reset()
        value = build_completion(*self.inputs(), self.base.now)
        changed = deepcopy(value)
        changed["targets"][0]["evidenceSha256"] = "f" * 64
        self.output.write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "differs"):
            verify_completion(self.output, *self.inputs())
        self.output.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate"):
            verify_completion(self.output, *self.inputs())

    def setUp_after_mutation_reset(self) -> None:
        evidence = json.loads(self.paths["chrome-previous"].read_text(encoding="utf-8"))
        evidence["sourceRevision"] = self.base.value["sourceRevision"]
        self.paths["chrome-previous"].write_text(json.dumps(evidence), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
