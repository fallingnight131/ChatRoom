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
from web_artifact_manifest import build_manifest, read_response_policy, write_manifest  # noqa: E402


class WebArtifactManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.site = self.root / "site"
        assets = self.site / "assets"
        assets.mkdir(parents=True)
        (assets / "index-AbCd1234.js").write_text("console.log('ok')\n", encoding="utf-8")
        (assets / "index-XyZ_9876.css").write_text("body{}\n", encoding="utf-8")
        (self.site / "index.html").write_text(
            '<link rel="stylesheet" href="/assets/index-XyZ_9876.css">'
            '<script type="module" src="/assets/index-AbCd1234.js"></script>\n',
            encoding="utf-8",
        )
        self.package = self.root / "package.json"
        self.package.write_text(
            json.dumps({"name": "chatroom-web", "version": "1.0.0", "private": True}),
            encoding="utf-8",
        )
        (self.root / "package-lock.json").write_text(json.dumps({
            "name": "chatroom-web",
            "version": "1.0.0",
            "packages": {"": {"name": "chatroom-web", "version": "1.0.0"}},
        }), encoding="utf-8")
        self.policy = self.root / "response-policy.json"
        self.policy.write_text(
            (ROOT / "packaging" / "web" / "response-policy.json").read_text(encoding="utf-8"),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def build(self):
        return build_manifest(self.site, self.package, "b" * 40, self.policy)

    def test_builds_deterministic_cache_classified_manifest(self) -> None:
        first, checksums = self.build()
        second, repeated_checksums = self.build()
        self.assertEqual(first, second)
        self.assertEqual(checksums, repeated_checksums)
        self.assertEqual(first["releaseStatus"], "unsigned-not-deployed-verification-only")
        self.assertEqual(first["schemaVersion"], 2)
        self.assertEqual(first["responsePolicy"]["applicationStatus"], "required-not-observed")
        self.assertEqual(first["responsePolicy"]["requiredScheme"], "https")
        self.assertTrue(any(line.endswith("  response-policy.json") for line in checksums))
        policies = {entry["path"]: entry["cacheControl"] for entry in first["files"]}
        self.assertEqual(policies["site/index.html"], "no-store")
        self.assertEqual(policies["site/assets/index-AbCd1234.js"], "public,max-age=31536000,immutable")

        output = self.root / "artifact"
        output.mkdir()
        (output / "response-policy.json").write_bytes(self.policy.read_bytes())
        write_manifest(output, first, checksums)
        self.assertEqual(
            json.loads((output / "web-artifact-manifest.json").read_text(encoding="utf-8")),
            first,
        )

    def test_rejects_package_version_drift(self) -> None:
        self.package.write_text(
            json.dumps({"name": "chatroom-web", "version": "2.0.0", "private": True}),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ManifestError, "one canonical SemVer"):
            self.build()

        self.package.write_text(
            json.dumps({"name": "chatroom-web", "version": [1, 0, 0], "private": True}),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ManifestError, "one canonical SemVer"):
            self.build()

    def test_rejects_external_inline_missing_and_unhashed_entrypoints(self) -> None:
        cases = [
            '<script src="https://cdn.example.test/app.js"></script>',
            "<script>console.log('inline')</script>",
            '<script src="/assets/missing-AbCd1234.js"></script>',
            '<script src="/assets/index.js"></script>',
        ]
        for entrypoint in cases:
            with self.subTest(entrypoint=entrypoint):
                (self.site / "index.html").write_text(entrypoint, encoding="utf-8")
                with self.assertRaises(ManifestError):
                    self.build()

    def test_rejects_source_maps_and_unhashed_payload_assets(self) -> None:
        source_map = self.site / "assets" / "index-AbCd1234.js.map"
        source_map.write_text("{}", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "source maps"):
            self.build()
        source_map.unlink()

        script = self.site / "assets" / "index-AbCd1234.js"
        script.write_text("console.log('ok')\n//# sourceMappingURL=hidden.map\n", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "source maps"):
            self.build()
        script.write_text("console.log('ok')\n", encoding="utf-8")

        unhashed = self.site / "assets" / "debug.js"
        unhashed.write_text("console.log('debug')", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "content-hashed"):
            self.build()

    def test_rejects_weakened_or_incomplete_response_policy(self) -> None:
        original = json.loads(self.policy.read_text(encoding="utf-8"))
        mutations = [
            lambda policy: policy.update(requiredScheme="http"),
            lambda policy: policy.update(sourceMaps="published"),
            lambda policy: policy["securityHeaders"].update({"Content-Security-Policy": "default-src *"}),
            lambda policy: policy["securityHeaders"].pop("Strict-Transport-Security"),
            lambda policy: policy["cacheControl"].update({"hashedAssets": "no-cache"}),
            lambda policy: policy["releaseIdentityHeaders"].pop("X-ChatRoom-Source-Revision"),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                policy = json.loads(json.dumps(original))
                mutate(policy)
                self.policy.write_text(json.dumps(policy), encoding="utf-8")
                with self.assertRaises(ManifestError):
                    read_response_policy(self.policy)


if __name__ == "__main__":
    unittest.main()
