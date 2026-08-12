#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import unittest
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError  # noqa: E402
import web_browser_host_evidence_test as release_fixture  # noqa: E402
from web_release_health_window import build_window, verify_window, write_once  # noqa: E402


class WebReleaseHealthWindowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.base = release_fixture.WebBrowserHostEvidenceTest(
            methodName="test_accepts_exact_branded_browser_candidate_smoke")
        self.base.setUp()
        self.release_paths = []
        self.route_paths = []
        manifest_sha = self.base.value["artifactManifestSha256"]
        for index, second in enumerate((0, 30, 60)):
            release_time = f"2026-08-13T12:{second // 60:02d}:{second % 60:02d}+00:00"
            route_time = f"2026-08-13T12:{second // 60:02d}:{second % 60 + 5:02d}+00:00"
            release = {
                "schemaVersion": 1,
                "evidenceType": "web-release-https-observation",
                "status": "healthy",
                "baseUrl": "https://preview.example.test",
                "releaseId": self.base.value["releaseId"],
                "version": self.base.value["version"],
                "sourceRevision": self.base.value["sourceRevision"],
                "artifactManifestSha256": manifest_sha,
                "responsePolicySha256": json.loads(
                    (self.base.release / "web-artifact-manifest.json").read_text()
                )["responsePolicy"]["sha256"],
                "observedFileCount": 2,
                "observedPaths": ["/assets/index-AbCd1234.js", "/index.html"],
                "observedAt": release_time,
            }
            routes = {
                "schemaVersion": 1,
                "evidenceType": "web-application-route-observation",
                "status": "healthy",
                "baseUrl": "https://preview.example.test",
                "apiHealthPath": "/api/health",
                "webSocketPath": "/ws",
                "apiStatus": 200,
                "apiProtocol": "v1",
                "webSocketStatus": 101,
                "observedAt": route_time,
            }
            release_path = self.base.fixture.root / f"release-{index}.json"
            route_path = self.base.fixture.root / f"route-{index}.json"
            release_path.write_text(json.dumps(release), encoding="utf-8")
            route_path.write_text(json.dumps(routes), encoding="utf-8")
            self.release_paths.append(release_path)
            self.route_paths.append(route_path)
        self.now = datetime(2026, 8, 13, 12, 1, 10, tzinfo=timezone.utc)
        self.output = self.base.fixture.root / "health-window.json"

    def tearDown(self) -> None:
        self.base.tearDown()

    def inputs(self):
        return self.release_paths, self.route_paths, self.base.release, "preview"

    def test_closes_repeated_static_and_route_health_once(self) -> None:
        value = build_window(*self.inputs(), self.now)
        write_once(self.output, value)
        self.assertEqual(value["status"], "sustained-healthy")
        self.assertEqual(value["sampleCount"], 3)
        self.assertEqual(verify_window(self.output, *self.inputs()), value)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.output, value)

    def test_rejects_short_split_duplicate_and_mixed_origin_windows(self) -> None:
        release = json.loads(self.release_paths[2].read_text(encoding="utf-8"))
        routes = json.loads(self.route_paths[2].read_text(encoding="utf-8"))
        release["observedAt"] = "2026-08-13T12:00:50+00:00"
        routes["observedAt"] = "2026-08-13T12:00:55+00:00"
        self.release_paths[2].write_text(json.dumps(release), encoding="utf-8")
        self.route_paths[2].write_text(json.dumps(routes), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "bounded window"):
            build_window(*self.inputs(), self.now)
        release["observedAt"] = "2026-08-13T12:01:00+00:00"
        routes["observedAt"] = "2026-08-13T12:01:05+00:00"
        self.release_paths[2].write_text(json.dumps(release), encoding="utf-8")
        self.route_paths[2].write_text(json.dumps(routes), encoding="utf-8")
        routes = json.loads(self.route_paths[1].read_text(encoding="utf-8"))
        routes["observedAt"] = "2026-08-13T12:02:00+00:00"
        self.route_paths[1].write_text(json.dumps(routes), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "too far apart"):
            build_window(*self.inputs(), self.now)
        routes["observedAt"] = "2026-08-13T12:00:35+00:00"
        routes["baseUrl"] = "https://other.example.test"
        self.route_paths[1].write_text(json.dumps(routes), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "one release origin"):
            build_window(*self.inputs(), self.now)

    def test_rejects_mutated_and_duplicate_completion(self) -> None:
        value = build_window(*self.inputs(), self.now)
        changed = deepcopy(value)
        changed["samples"][0]["releaseObservationSha256"] = "f" * 64
        self.output.write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "differs"):
            verify_window(self.output, *self.inputs())
        self.output.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate"):
            verify_window(self.output, *self.inputs())


if __name__ == "__main__":
    unittest.main()
