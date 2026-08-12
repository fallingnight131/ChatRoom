#!/usr/bin/env python3

from __future__ import annotations

import json
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from artifact_manifest_common import ManifestError  # noqa: E402
from web_artifact_manifest import build_manifest, read_response_policy, write_manifest  # noqa: E402
from web_release_probe import probe_release  # noqa: E402


class IsolatedReleaseServer:
    def __init__(self, artifact: Path, certificate: Path, private_key: Path) -> None:
        self.artifact = artifact
        self.policy = read_response_policy(artifact / "response-policy.json")
        self.manifest = json.loads((artifact / "web-artifact-manifest.json").read_text(encoding="utf-8"))
        self.header_to_omit: str | None = None
        self.wrong_cache = False
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                relative = "index.html" if self.path == "/index.html" else self.path.removeprefix("/")
                entry = next(
                    (item for item in fixture.manifest["files"] if item["path"] == f"site/{relative}"),
                    None,
                )
                if entry is None:
                    self.send_error(404)
                    return
                body = (fixture.artifact / entry["path"]).read_bytes()
                self.send_response(200)
                for name, value in fixture.policy["securityHeaders"].items():
                    if name != fixture.header_to_omit:
                        self.send_header(name, value)
                values = {"version": fixture.manifest["version"], "sourceRevision": fixture.manifest["sourceRevision"]}
                for name, template in fixture.policy["releaseIdentityHeaders"].items():
                    self.send_header(name, template.format(**values))
                self.send_header("Cache-Control", "no-cache" if fixture.wrong_cache else entry["cacheControl"])
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format: str, *args) -> None:
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(certificate, private_key)
        self.server.socket = context.wrap_socket(self.server.socket, server_side=True)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        return f"https://localhost:{self.server.server_port}/"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, exception_type, exception, traceback) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


class WebReleaseProbeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.artifact = self._artifact()
        self.certificate = self.root / "localhost.crt"
        self.private_key = self.root / "localhost.key"
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-keyout", str(self.private_key), "-out", str(self.certificate),
            "-days", "1", "-subj", "/CN=localhost",
            "-addext", "subjectAltName=DNS:localhost",
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _artifact(self) -> Path:
        artifact = self.root / "artifact"
        site = artifact / "site"
        assets = site / "assets"
        assets.mkdir(parents=True)
        (assets / "index-AbCd1234.js").write_text("console.log('release')\n", encoding="utf-8")
        (site / "index.html").write_text(
            '<script type="module" src="/assets/index-AbCd1234.js"></script>\n', encoding="utf-8",
        )
        package = artifact / "package.json"
        package.write_text(json.dumps({"name": "chatroom-web", "version": "1.2.3", "private": True}))
        (artifact / "package-lock.json").write_text(json.dumps({
            "name": "chatroom-web", "version": "1.2.3",
            "packages": {"": {"name": "chatroom-web", "version": "1.2.3"}},
        }))
        policy = artifact / "response-policy.json"
        policy.write_bytes((ROOT / "packaging/web/response-policy.json").read_bytes())
        manifest, checksums = build_manifest(site, package, "a" * 40, policy)
        package.unlink()
        (artifact / "package-lock.json").unlink()
        write_manifest(artifact, manifest, checksums)
        return artifact

    def test_observes_exact_https_headers_identity_cache_and_bytes(self) -> None:
        with IsolatedReleaseServer(self.artifact, self.certificate, self.private_key) as server:
            evidence = probe_release(server.base_url, self.artifact, self.certificate)
        self.assertEqual(evidence["status"], "healthy")
        self.assertEqual(evidence["version"], "1.2.3")
        self.assertEqual(evidence["observedFileCount"], 2)
        self.assertEqual(evidence["observedPaths"], ["/assets/index-AbCd1234.js", "/index.html"])

    def test_rejects_missing_security_header_and_wrong_cache_class(self) -> None:
        with IsolatedReleaseServer(self.artifact, self.certificate, self.private_key) as server:
            server.header_to_omit = "Content-Security-Policy"
            with self.assertRaisesRegex(ManifestError, "Content-Security-Policy"):
                probe_release(server.base_url, self.artifact, self.certificate)

        with IsolatedReleaseServer(self.artifact, self.certificate, self.private_key) as server:
            server.wrong_cache = True
            with self.assertRaisesRegex(ManifestError, "Cache-Control"):
                probe_release(server.base_url, self.artifact, self.certificate)

    def test_rejects_http_and_untrusted_certificates(self) -> None:
        with self.assertRaisesRegex(ManifestError, "HTTPS origin"):
            probe_release("http://localhost:8080", self.artifact, self.certificate)
        with IsolatedReleaseServer(self.artifact, self.certificate, self.private_key) as server:
            with self.assertRaisesRegex(ManifestError, "HTTPS request failed"):
                probe_release(server.base_url, self.artifact)


if __name__ == "__main__":
    unittest.main()
