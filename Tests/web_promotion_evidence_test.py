#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from artifact_manifest_common import ManifestError  # noqa: E402
from web_artifact_manifest import build_manifest, write_manifest  # noqa: E402
from web_promotion_evidence import (  # noqa: E402
    build_promotion_evidence, verify_promotion_evidence, write_promotion_once,
)


class WebPromotionEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.now = datetime(2026, 8, 12, 2, 10, tzinfo=timezone.utc)
        self.current = self._artifact("1.1.0", "b" * 40, "current")
        self.rollback = self._artifact("1.0.0", "a" * 40, "rollback")
        self.current_observation = self._release_observation(
            self.current, "2026-08-12T02:05:00+00:00",
        )
        self.rollback_observation = self._release_observation(
            self.rollback, "2026-08-01T02:00:00+00:00",
        )
        self.route_observation = self.root / "routes.json"
        self.route_observation.write_text(json.dumps({
            "schemaVersion": 1,
            "evidenceType": "web-application-route-observation",
            "status": "healthy",
            "baseUrl": "https://chat.example.test",
            "apiHealthPath": "/api/health",
            "webSocketPath": "/ws",
            "apiStatus": 200,
            "apiProtocol": "v1",
            "webSocketStatus": 101,
            "observedAt": "2026-08-12T02:06:00+00:00",
        }, sort_keys=True), encoding="utf-8")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _artifact(self, version: str, revision: str, body: str) -> Path:
        artifact = self.root / f"artifact-{version}"
        site = artifact / "site"
        assets = site / "assets"
        assets.mkdir(parents=True)
        (assets / "index-AbCd1234.js").write_text(body, encoding="utf-8")
        (site / "index.html").write_text(
            '<script type="module" src="/assets/index-AbCd1234.js"></script>\n',
            encoding="utf-8",
        )
        package = artifact / "package.json"
        package.write_text(json.dumps({"name": "chatroom-web", "version": version, "private": True}))
        (artifact / "package-lock.json").write_text(json.dumps({
            "name": "chatroom-web", "version": version,
            "packages": {"": {"name": "chatroom-web", "version": version}},
        }))
        policy = artifact / "response-policy.json"
        policy.write_bytes((ROOT / "packaging/web/response-policy.json").read_bytes())
        manifest, checksums = build_manifest(site, package, revision, policy)
        package.unlink()
        (artifact / "package-lock.json").unlink()
        write_manifest(artifact, manifest, checksums)
        return artifact

    def _release_observation(self, artifact: Path, observed_at: str) -> Path:
        manifest_bytes = (artifact / "web-artifact-manifest.json").read_bytes()
        manifest = json.loads(manifest_bytes)
        path = self.root / f"observation-{manifest['version']}.json"
        path.write_text(json.dumps({
            "schemaVersion": 1,
            "evidenceType": "web-release-https-observation",
            "status": "healthy",
            "baseUrl": "https://chat.example.test",
            "releaseId": f"{manifest['version']}-{manifest['sourceRevision']}",
            "version": manifest["version"],
            "sourceRevision": manifest["sourceRevision"],
            "artifactManifestSha256": hashlib.sha256(manifest_bytes).hexdigest(),
            "responsePolicySha256": manifest["responsePolicy"]["sha256"],
            "observedFileCount": len(manifest["files"]),
            "observedPaths": sorted("/" + entry["path"].removeprefix("site/") for entry in manifest["files"]),
            "observedAt": observed_at,
        }, sort_keys=True), encoding="utf-8")
        return path

    def _build(self):
        return build_promotion_evidence(
            self.current, self.current_observation, self.route_observation,
            self.rollback, self.rollback_observation, self.now, 900,
        )

    def test_binds_fresh_release_routes_and_distinct_retained_rollback(self) -> None:
        evidence = self._build()
        self.assertEqual(evidence["status"], "technical-gates-observed-not-published")
        self.assertNotEqual(evidence["releaseId"], evidence["rollbackReleaseId"])
        output = self.root / "promotion.json"
        write_promotion_once(output, evidence)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_promotion_once(output, evidence)
        self.assertEqual(verify_promotion_evidence(
            output, self.current, self.current_observation, self.route_observation,
            self.rollback, self.rollback_observation,
        ), evidence)
        route = json.loads(self.route_observation.read_text(encoding="utf-8"))
        route["observedAt"] = "2026-08-12T02:06:01+00:00"
        self.route_observation.write_text(json.dumps(route), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "does not match"):
            verify_promotion_evidence(
                output, self.current, self.current_observation, self.route_observation,
                self.rollback, self.rollback_observation,
            )

    def test_rejects_stale_future_and_split_window_observations(self) -> None:
        current = json.loads(self.current_observation.read_text(encoding="utf-8"))
        current["observedAt"] = "2026-08-12T01:00:00+00:00"
        self.current_observation.write_text(json.dumps(current), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "stale or from the future"):
            self._build()

        current["observedAt"] = "2026-08-12T02:09:00+00:00"
        self.current_observation.write_text(json.dumps(current), encoding="utf-8")
        route = json.loads(self.route_observation.read_text(encoding="utf-8"))
        route["observedAt"] = "2026-08-12T02:01:00+00:00"
        self.route_observation.write_text(json.dumps(route), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "promotion window"):
            self._build()

        route["observedAt"] = "2026-08-12T02:11:00+00:00"
        self.route_observation.write_text(json.dumps(route), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "future"):
            self._build()

    def test_rejects_different_origin_same_rollback_and_unknown_record_fields(self) -> None:
        route = json.loads(self.route_observation.read_text(encoding="utf-8"))
        route["baseUrl"] = "https://other.example.test"
        self.route_observation.write_text(json.dumps(route), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "one HTTPS origin"):
            self._build()

        route["baseUrl"] = "https://chat.example.test"
        self.route_observation.write_text(json.dumps(route), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "different release"):
            build_promotion_evidence(
                self.current, self.current_observation, self.route_observation,
                self.current, self.current_observation, self.now, 900,
            )

        evidence = self._build()
        output = self.root / "promotion.json"
        output.write_text(json.dumps({**evidence, "operator": "untrusted"}), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "unsupported shape"):
            verify_promotion_evidence(
                output, self.current, self.current_observation, self.route_observation,
                self.rollback, self.rollback_observation,
            )


if __name__ == "__main__":
    unittest.main()
