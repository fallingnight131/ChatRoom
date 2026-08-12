#!/usr/bin/env python3
"""Observe same-origin V1 HTTP health and WebSocket upgrade routes over trusted TLS."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import secrets
import socket
import ssl
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPSHandler, Request, build_opener

from artifact_manifest_common import ManifestError
from web_release_probe import RejectRedirects, _origin_url, write_observation


WEBSOCKET_GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
ROUTE_KEYS = {
    "schemaVersion", "evidenceType", "status", "baseUrl", "apiHealthPath",
    "webSocketPath", "apiStatus", "apiProtocol", "webSocketStatus", "observedAt",
}


def _path(value: str, label: str) -> str:
    if (not value.startswith("/") or value.startswith("//") or "\\" in value
            or "?" in value or "#" in value or value.endswith("/")
            or any(character not in "/-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
                   for character in value)
            or any(part in {"", ".", ".."} for part in value.split("/")[1:])):
        raise ManifestError(f"{label} must be one exact same-origin absolute path")
    return value


def _context(ca_certificate: Path | None) -> ssl.SSLContext:
    context = ssl.create_default_context(cafile=str(ca_certificate) if ca_certificate else None)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    return context


def _probe_api(origin: str, path: str, context: ssl.SSLContext) -> None:
    opener = build_opener(RejectRedirects(), HTTPSHandler(context=context))
    request = Request(
        origin + path,
        headers={"Accept": "application/json", "Accept-Encoding": "identity"},
        method="GET",
    )
    try:
        with opener.open(request, timeout=10) as response:
            if response.status != 200 or response.url != origin + path:
                raise ManifestError("Web API health status or URL is unexpected")
            expected = {
                "Content-Type": "application/json; charset=utf-8",
                "Cache-Control": "no-store",
                "X-Content-Type-Options": "nosniff",
                "Content-Length": "32",
            }
            for name, value in expected.items():
                if (response.headers.get_all(name) or []) != [value]:
                    raise ManifestError(f"Web API health response header mismatch: {name}")
            for forbidden in ("Content-Encoding", "Set-Cookie", "Access-Control-Allow-Origin"):
                if response.headers.get_all(forbidden):
                    raise ManifestError(f"Web API health must not include {forbidden}")
            if response.read(65) != b'{"protocol":"v1","status":"ok"}\n':
                raise ManifestError("Web API health body is unexpected")
    except ManifestError:
        raise
    except (HTTPError, URLError, OSError) as error:
        raise ManifestError("Web API health HTTPS request failed") from error


def _read_headers(stream: ssl.SSLSocket) -> tuple[str, dict[str, list[str]]]:
    response = bytearray()
    while b"\r\n\r\n" not in response:
        chunk = stream.recv(4096)
        if not chunk:
            raise ManifestError("WebSocket upgrade closed before complete headers")
        response.extend(chunk)
        if len(response) > 16 * 1024:
            raise ManifestError("WebSocket upgrade response headers are oversized")
    header_bytes, remainder = bytes(response).split(b"\r\n\r\n", 1)
    if remainder:
        raise ManifestError("WebSocket upgrade returned unexpected response bytes")
    try:
        lines = header_bytes.decode("ascii").split("\r\n")
    except UnicodeDecodeError as error:
        raise ManifestError("WebSocket upgrade headers are not ASCII") from error
    headers: dict[str, list[str]] = {}
    for line in lines[1:]:
        if ":" not in line:
            raise ManifestError("WebSocket upgrade header is malformed")
        name, value = line.split(":", 1)
        normalized = name.strip().lower()
        if not normalized:
            raise ManifestError("WebSocket upgrade header name is empty")
        headers.setdefault(normalized, []).append(value.strip())
    return lines[0], headers


def _probe_websocket(origin: str, path: str, context: ssl.SSLContext) -> None:
    parsed = urlsplit(origin)
    host = parsed.hostname
    if host is None:
        raise ManifestError("WebSocket probe origin has no host")
    port = parsed.port or 443
    key = base64.b64encode(secrets.token_bytes(16)).decode("ascii")
    host_header = parsed.netloc
    request = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host_header}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        f"Origin: {origin}\r\n\r\n"
    ).encode("ascii")
    try:
        with socket.create_connection((host, port), timeout=10) as connection:
            with context.wrap_socket(connection, server_hostname=host) as stream:
                stream.settimeout(10)
                stream.sendall(request)
                status_line, headers = _read_headers(stream)
    except ManifestError:
        raise
    except (OSError, ssl.SSLError) as error:
        raise ManifestError("WebSocket upgrade HTTPS request failed") from error
    status_parts = status_line.split(" ", 2)
    if len(status_parts) < 2 or status_parts[:2] != ["HTTP/1.1", "101"]:
        raise ManifestError("WebSocket upgrade status is unexpected")
    if headers.get("upgrade") != ["websocket"]:
        raise ManifestError("WebSocket Upgrade header is unexpected")
    connections = headers.get("connection")
    if (connections is None or len(connections) != 1
            or "upgrade" not in {token.strip().lower() for token in connections[0].split(",")}):
        raise ManifestError("WebSocket Connection header is unexpected")
    expected_accept = base64.b64encode(hashlib.sha1(key.encode("ascii") + WEBSOCKET_GUID).digest()).decode("ascii")
    if headers.get("sec-websocket-accept") != [expected_accept]:
        raise ManifestError("WebSocket accept challenge is unexpected")
    for forbidden in (
        "location", "set-cookie", "access-control-allow-origin", "sec-websocket-protocol",
    ):
        if headers.get(forbidden):
            raise ManifestError(f"WebSocket upgrade must not include {forbidden}")


def probe_application_routes(
    base_url: str,
    ca_certificate: Path | None = None,
    api_health_path: str = "/api/health",
    websocket_path: str = "/ws",
) -> dict[str, object]:
    origin = _origin_url(base_url)
    api_path = _path(api_health_path, "API health path")
    ws_path = _path(websocket_path, "WebSocket path")
    if api_path == ws_path:
        raise ManifestError("Web API and WebSocket routes must be distinct")
    context = _context(ca_certificate)
    _probe_api(origin, api_path, context)
    _probe_websocket(origin, ws_path, context)
    return {
        "schemaVersion": 1,
        "evidenceType": "web-application-route-observation",
        "status": "healthy",
        "baseUrl": origin,
        "apiHealthPath": api_path,
        "webSocketPath": ws_path,
        "apiStatus": 200,
        "apiProtocol": "v1",
        "webSocketStatus": 101,
        "observedAt": datetime.now(timezone.utc).isoformat(),
    }


def read_route_observation(path: Path) -> dict[str, object]:
    try:
        evidence = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        raise ManifestError("Web application route evidence is unreadable") from error
    if not isinstance(evidence, dict) or set(evidence) != ROUTE_KEYS:
        raise ManifestError("Web application route evidence has an unsupported shape")
    if (evidence.get("schemaVersion") != 1
            or evidence.get("evidenceType") != "web-application-route-observation"
            or evidence.get("status") != "healthy"
            or evidence.get("apiStatus") != 200
            or evidence.get("apiProtocol") != "v1"
            or evidence.get("webSocketStatus") != 101):
        raise ManifestError("Web application route evidence status is unsupported")
    _origin_url(str(evidence.get("baseUrl", "")))
    _path(str(evidence.get("apiHealthPath", "")), "API health path")
    _path(str(evidence.get("webSocketPath", "")), "WebSocket path")
    try:
        observed_at = datetime.fromisoformat(str(evidence.get("observedAt")))
    except ValueError as error:
        raise ManifestError("Web application route evidence time is malformed") from error
    if observed_at.tzinfo is None:
        raise ManifestError("Web application route evidence time must include a timezone")
    return evidence


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--ca-certificate", type=Path)
    parser.add_argument("--api-health-path", default="/api/health")
    parser.add_argument("--websocket-path", default="/ws")
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        evidence = probe_application_routes(
            args.base_url, args.ca_certificate, args.api_health_path, args.websocket_path,
        )
        if args.output:
            write_observation(args.output, evidence)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web application route probe failed: {error}") from None
    print(json.dumps(evidence, ensure_ascii=True, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
