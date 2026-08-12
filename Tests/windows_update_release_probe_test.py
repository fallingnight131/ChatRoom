#!/usr/bin/env python3

from __future__ import annotations

import json
import ssl
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError, atomic_write  # noqa: E402
from windows_update_channel_candidate import assemble_candidate  # noqa: E402
from windows_update_channel_candidate_test import (  # noqa: E402
    WindowsUpdateChannelCandidateTest,
)
from windows_update_manifest import canonical_bytes, sign_manifest  # noqa: E402
from windows_update_release_probe import (  # noqa: E402
    probe_release, read_observation, write_observation,
)


class IsolatedUpdateServer:
    def __init__(self, certificate: Path, private_key: Path) -> None:
        self.candidate: Path | None = None
        self.redirect = False
        self.wrong_bytes = False
        self.omit_header: str | None = None
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                if fixture.redirect:
                    self.send_response(302)
                    self.send_header("Location", self.path)
                    self.end_headers()
                    return
                if fixture.candidate is None:
                    self.send_error(503)
                    return
                version = "1.2.3"
                entries = {
                    "/stable/manifest.json": (
                        fixture.candidate / "update/manifest.json",
                        "application/json", "no-store"),
                    "/stable/manifest.json.sig": (
                        fixture.candidate / "update/manifest.json.sig",
                        "application/octet-stream", "no-store"),
                    f"/stable/ChatRoom-{version}-Setup.exe": (
                        fixture.candidate / f"windows/installer/ChatRoom-{version}-Setup.exe",
                        "application/vnd.microsoft.portable-executable",
                        "public, max-age=31536000, immutable"),
                }
                entry = entries.get(self.path)
                if entry is None:
                    self.send_error(404)
                    return
                body = entry[0].read_bytes()
                if fixture.wrong_bytes and self.path.endswith("Setup.exe"):
                    body = b"x" * len(body)
                self.send_response(200)
                headers = {
                    "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
                    "X-Content-Type-Options": "nosniff",
                    "Content-Type": entry[1],
                    "Cache-Control": entry[2],
                    "Content-Length": str(len(body)),
                }
                for name, value in headers.items():
                    if name != fixture.omit_header:
                        self.send_header(name, value)
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
        return f"https://localhost:{self.server.server_port}/stable"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, exception_type, exception, traceback) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


class WindowsUpdateReleaseProbeTest(WindowsUpdateChannelCandidateTest):
    def prepare_server_and_candidate(self):
        certificate = self.root / "localhost.crt"
        tls_key = self.root / "localhost.key"
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-keyout", str(tls_key), "-out", str(certificate), "-days", "1",
            "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        server = IsolatedUpdateServer(certificate, tls_key)
        self.prepare_update_inputs()
        manifest = json.loads(self.update_manifest.read_text(encoding="utf-8"))
        manifest["installer"]["url"] = (
            f"{server.base_url}/ChatRoom-{self.version}-Setup.exe")
        self.update_manifest.unlink()
        self.update_signature.unlink()
        atomic_write(self.update_manifest, canonical_bytes(manifest).decode("utf-8"))
        sign_manifest(self.update_manifest, self.private_key, self.update_signature)
        assemble_candidate(
            self.candidate, self.update_manifest, self.update_signature,
            self.public_key, self.update_candidate, self.version_file,
            self.revision, "stable", "6.11.1", self.signer,
            self.public_digest(), self.now,
        )
        server.candidate = self.update_candidate
        return server, certificate

    def probe(self, server, certificate=None):
        return probe_release(
            f"{server.base_url}/manifest.json", self.update_candidate,
            self.version_file, self.revision, "stable", "6.11.1", self.signer,
            self.public_digest(), certificate, self.now,
        )

    def test_observes_exact_manifest_signature_and_installer_over_https(self) -> None:
        server, certificate = self.prepare_server_and_candidate()
        with server:
            evidence = self.probe(server, certificate)
        self.assertEqual(evidence["status"], "healthy")
        self.assertEqual(evidence["manifestSequence"], 42)
        output = self.root / "observation.json"
        write_observation(output, evidence)
        self.assertEqual(read_observation(output, self.update_candidate), evidence)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_observation(output, evidence)

    def test_rejects_wrong_bytes_missing_headers_and_redirects(self) -> None:
        server, certificate = self.prepare_server_and_candidate()
        with server:
            server.wrong_bytes = True
            with self.assertRaisesRegex(ManifestError, "bytes do not match"):
                self.probe(server, certificate)
            server.wrong_bytes = False
            server.omit_header = "Strict-Transport-Security"
            with self.assertRaisesRegex(ManifestError, "Strict-Transport-Security"):
                self.probe(server, certificate)
            server.omit_header = None
            server.redirect = True
            with self.assertRaisesRegex(ManifestError, "redirect"):
                self.probe(server, certificate)

    def test_rejects_untrusted_tls_and_non_colocated_manifest(self) -> None:
        server, certificate = self.prepare_server_and_candidate()
        with server:
            with self.assertRaisesRegex(ManifestError, "HTTPS request failed"):
                self.probe(server)
            with self.assertRaisesRegex(ManifestError, "not co-located"):
                probe_release(
                    f"https://localhost:{server.server.server_port}/other/manifest.json",
                    self.update_candidate, self.version_file, self.revision,
                    "stable", "6.11.1", self.signer, self.public_digest(),
                    certificate, self.now,
                )

    def test_rejects_observation_identity_mutation_and_duplicates(self) -> None:
        server, certificate = self.prepare_server_and_candidate()
        with server:
            evidence = self.probe(server, certificate)
        output = self.root / "observation.json"
        output.write_text(json.dumps({**evidence, "channel": "beta"}), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "does not match"):
            read_observation(output, self.update_candidate)
        output.write_text('{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            read_observation(output, self.update_candidate)


if __name__ == "__main__":
    import unittest
    unittest.main()
