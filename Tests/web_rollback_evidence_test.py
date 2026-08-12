#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from artifact_manifest_common import ManifestError  # noqa: E402
from web_rollback_evidence import (  # noqa: E402
    build_rollback_evidence, verify_rollback_evidence, write_once,
)


class WebRollbackEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.prior = self._observation("1.0.0", "a" * 40, "2026-08-12T01:00:00+00:00")
        self.current = self._observation("1.1.0", "b" * 40, "2026-08-12T01:01:00+00:00")
        self.restored = self._observation("1.0.0", "a" * 40, "2026-08-12T01:02:00+00:00")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _observation(self, version: str, revision: str, observed_at: str) -> Path:
        release_id = f"{version}-{revision}"
        time_label = observed_at[11:19].replace(":", "")
        path = self.root / f"{release_id}-{time_label}.json"
        path.write_text(json.dumps({
            "schemaVersion": 1,
            "evidenceType": "web-release-https-observation",
            "status": "healthy",
            "baseUrl": "https://chat.example.test",
            "releaseId": release_id,
            "version": version,
            "sourceRevision": revision,
            "artifactManifestSha256": revision[0] * 64,
            "responsePolicySha256": "c" * 64,
            "observedFileCount": 2,
            "observedPaths": ["/assets/index-AbCd1234.js", "/index.html"],
            "observedAt": observed_at,
        }, sort_keys=True), encoding="utf-8")
        return path

    def test_binds_prior_current_and_restored_observations_and_verifies_hashes(self) -> None:
        evidence = build_rollback_evidence(self.prior, self.current, self.restored)
        output = self.root / "rollback.json"
        write_once(output, evidence)
        self.assertEqual(
            verify_rollback_evidence(output, self.prior, self.current, self.restored), evidence,
        )
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(output, evidence)
        current = json.loads(self.current.read_text(encoding="utf-8"))
        current["observedAt"] = "2026-08-12T01:01:30+00:00"
        self.current.write_text(json.dumps(current), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "does not match"):
            verify_rollback_evidence(output, self.prior, self.current, self.restored)

    def test_rejects_different_origin_identity_and_invalid_order(self) -> None:
        restored = json.loads(self.restored.read_text(encoding="utf-8"))
        restored["baseUrl"] = "https://other.example.test"
        self.restored.write_text(json.dumps(restored), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "one HTTPS origin"):
            build_rollback_evidence(self.prior, self.current, self.restored)

        restored["baseUrl"] = "https://chat.example.test"
        restored["artifactManifestSha256"] = "d" * 64
        self.restored.write_text(json.dumps(restored), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "prior verified"):
            build_rollback_evidence(self.prior, self.current, self.restored)

        restored["artifactManifestSha256"] = "a" * 64
        restored["observedAt"] = "2026-08-12T00:59:00+00:00"
        self.restored.write_text(json.dumps(restored), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "order"):
            build_rollback_evidence(self.prior, self.current, self.restored)

    def test_rejects_unknown_rollback_fields(self) -> None:
        evidence = build_rollback_evidence(self.prior, self.current, self.restored)
        output = self.root / "rollback.json"
        output.write_text(json.dumps({**evidence, "note": "untrusted"}), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "unsupported shape"):
            verify_rollback_evidence(output, self.prior, self.current, self.restored)


if __name__ == "__main__":
    unittest.main()
