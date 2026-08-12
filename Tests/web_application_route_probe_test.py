#!/usr/bin/env python3

from __future__ import annotations

import base64
import hashlib
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
from web_application_route_probe import (  # noqa: E402
    WEBSOCKET_GUID, probe_application_routes, read_route_observation,
)
from web_release_probe import write_observation  # noqa: E402


class RouteServer:
    def __init__(self, certificate: Path, private_key: Path) -> None:
        self.bad_health = False
        self.bad_accept = False
        self.duplicate_accept = False
        self.health_redirect = False
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_GET(self) -> None:
                if self.path == "/api/health":
                    if fixture.health_redirect:
                        self.send_response(302)
                        self.send_header("Location", "/other")
                        self.end_headers()
                        return
                    body = b'{"protocol":"v2","status":"ok"}\n' if fixture.bad_health else b'{"protocol":"v1","status":"ok"}\n'
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_header("Cache-Control", "no-store")
                    self.send_header("X-Content-Type-Options", "nosniff")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                if self.path == "/ws" and self.headers.get("Upgrade", "").lower() == "websocket":
                    key = self.headers.get("Sec-WebSocket-Key", "")
                    accept = base64.b64encode(
                        hashlib.sha1(key.encode("ascii") + WEBSOCKET_GUID).digest()
                    ).decode("ascii")
                    self.send_response_only(101, "Switching Protocols")
                    self.send_header("Upgrade", "websocket")
                    self.send_header("Connection", "Upgrade")
                    self.send_header("Sec-WebSocket-Accept", "wrong" if fixture.bad_accept else accept)
                    if fixture.duplicate_accept:
                        self.send_header("Sec-WebSocket-Accept", accept)
                    self.end_headers()
                    return
                self.send_error(404)

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
        return f"https://localhost:{self.server.server_port}"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, exception_type, exception, traceback) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


class WebApplicationRouteProbeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
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

    def test_observes_api_and_websocket_on_one_trusted_origin_and_persists_once(self) -> None:
        output = self.root / "route-evidence.json"
        with RouteServer(self.certificate, self.private_key) as server:
            evidence = probe_application_routes(server.base_url, self.certificate)
        self.assertEqual(evidence["apiStatus"], 200)
        self.assertEqual(evidence["webSocketStatus"], 101)
        write_observation(output, evidence)
        self.assertEqual(read_route_observation(output), evidence)
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_observation(output, evidence)

    def test_rejects_wrong_health_redirect_and_websocket_challenge(self) -> None:
        with RouteServer(self.certificate, self.private_key) as server:
            server.bad_health = True
            with self.assertRaisesRegex(ManifestError, "body"):
                probe_application_routes(server.base_url, self.certificate)
        with RouteServer(self.certificate, self.private_key) as server:
            server.health_redirect = True
            with self.assertRaisesRegex(ManifestError, "redirect"):
                probe_application_routes(server.base_url, self.certificate)
        with RouteServer(self.certificate, self.private_key) as server:
            server.bad_accept = True
            with self.assertRaisesRegex(ManifestError, "accept challenge"):
                probe_application_routes(server.base_url, self.certificate)
        with RouteServer(self.certificate, self.private_key) as server:
            server.duplicate_accept = True
            with self.assertRaisesRegex(ManifestError, "accept challenge"):
                probe_application_routes(server.base_url, self.certificate)

    def test_rejects_insecure_origin_unsafe_paths_untrusted_tls_and_unknown_evidence(self) -> None:
        with self.assertRaisesRegex(ManifestError, "HTTPS origin"):
            probe_application_routes("http://localhost:8080", self.certificate)
        with self.assertRaisesRegex(ManifestError, "exact same-origin"):
            probe_application_routes("https://localhost", self.certificate, "/api/../health")
        with RouteServer(self.certificate, self.private_key) as server:
            with self.assertRaisesRegex(ManifestError, "HTTPS request failed"):
                probe_application_routes(server.base_url)
            evidence = probe_application_routes(server.base_url, self.certificate)
        output = self.root / "route.json"
        output.write_text(json.dumps({**evidence, "unknown": True}), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "unsupported shape"):
            read_route_observation(output)


if __name__ == "__main__":
    unittest.main()
