#!/usr/bin/env python3
"""Verify the exact unauthenticated V1 HTTP release-health contract."""

from __future__ import annotations

import argparse
import http.client
import tempfile
from pathlib import Path

from v1_room_message_reliability_test import start_server, stop_server
from v1_smoke_test import SmokeFailure, find_port_range


def request(port: int, method: str, target: str) -> tuple[int, list[tuple[str, str]], bytes]:
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
    try:
        connection.request(method, target, headers={"Connection": "close"})
        response = connection.getresponse()
        return response.status, response.getheaders(), response.read()
    finally:
        connection.close()


def header_values(headers: list[tuple[str, str]], name: str) -> list[str]:
    return [value for key, value in headers if key.lower() == name.lower()]


def run_test(server: Path) -> None:
    port = find_port_range()
    with tempfile.TemporaryDirectory(prefix="chat-room-http-health-") as temp_name:
        directory = Path(temp_name)
        process = start_server(server, directory, directory / "health.db", port)
        try:
            status, headers, body = request(port + 2, "GET", "/api/health")
            if status != 200 or body != b'{"protocol":"v1","status":"ok"}\n':
                raise SmokeFailure(f"unexpected HTTP health response: {status} {body!r}")
            expected_headers = {
                "Content-Type": "application/json; charset=utf-8",
                "Cache-Control": "no-store",
                "X-Content-Type-Options": "nosniff",
                "Content-Length": str(len(body)),
                "Connection": "close",
            }
            for name, expected in expected_headers.items():
                if header_values(headers, name) != [expected]:
                    raise SmokeFailure(f"unexpected HTTP health header {name}: {headers!r}")
            if header_values(headers, "Access-Control-Allow-Origin"):
                raise SmokeFailure("same-origin health endpoint must not emit wildcard CORS")

            for method, target, expected in (
                ("GET", "/api/health/", 404),
                ("GET", "/api/health?probe=1", 404),
                ("POST", "/api/health", 405),
            ):
                actual, _, _ = request(port + 2, method, target)
                if actual != expected:
                    raise SmokeFailure(
                        f"HTTP health boundary accepted {method} {target}: {actual}"
                    )
        finally:
            output = stop_server(process)
            if process.returncode not in (0, -15):
                raise SmokeFailure(f"server exited unexpectedly: {output}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True)
    args = parser.parse_args()
    run_test(args.server.resolve())
    print("V1 HTTP health verification passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
