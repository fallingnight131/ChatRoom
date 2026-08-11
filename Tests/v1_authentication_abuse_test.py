#!/usr/bin/env python3
"""Verify V1 gateway/account/IP authentication-abuse controls."""

from __future__ import annotations

import base64
import hashlib
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path
from typing import Callable, Union

from v1_smoke_test import (
    SmokeFailure,
    V1Client,
    data,
    find_port_range,
    login,
    register,
    wait_for_server,
)


RATE_LIMIT_ERROR = "认证请求过于频繁，请稍后重试"


class V1WebSocketClient:
    def __init__(self, host: str, port: int, label: str) -> None:
        self.label = label
        self.socket = socket.create_connection((host, port), timeout=5)
        self.buffer = b""
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        request = (
            f"GET / HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        ).encode("ascii")
        self.socket.sendall(request)

        response = b""
        while b"\r\n\r\n" not in response:
            chunk = self.socket.recv(4096)
            if not chunk:
                raise SmokeFailure(f"{label} WebSocket handshake closed")
            response += chunk
            if len(response) > 64 * 1024:
                raise SmokeFailure(f"{label} WebSocket handshake was oversized")
        headers, self.buffer = response.split(b"\r\n\r\n", 1)
        expected_accept = base64.b64encode(
            hashlib.sha1(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")
            ).digest()
        )
        if not headers.startswith(b"HTTP/1.1 101") or (
            b"sec-websocket-accept: " + expected_accept.lower()
        ) not in headers.lower():
            raise SmokeFailure(f"{label} WebSocket handshake failed: {headers!r}")

    def close(self) -> None:
        self.socket.close()

    def send(self, message_type: str, payload: dict[str, object]) -> None:
        message = json.dumps(
            {
                "type": message_type,
                "id": str(uuid.uuid4()),
                "timestamp": int(time.time() * 1000),
                "data": payload,
            },
            separators=(",", ":"),
            ensure_ascii=False,
        ).encode("utf-8")
        self._send_frame(0x1, message)

    def receive_type(self, expected_type: str, timeout: float = 5) -> dict[str, object]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            opcode, payload = self._receive_frame(max(0.1, deadline - time.monotonic()))
            if opcode == 0x9:
                self._send_frame(0xA, payload)
                continue
            if opcode == 0x8:
                raise SmokeFailure(f"{self.label} WebSocket closed while reading")
            if opcode != 0x1:
                continue
            message = json.loads(payload.decode("utf-8"))
            if isinstance(message, dict) and message.get("type") == expected_type:
                return message
        raise SmokeFailure(f"{self.label} timed out waiting for {expected_type}")

    def _send_frame(self, opcode: int, payload: bytes) -> None:
        mask = os.urandom(4)
        length = len(payload)
        if length < 126:
            header = bytes([0x80 | opcode, 0x80 | length])
        elif length <= 0xFFFF:
            header = bytes([0x80 | opcode, 0xFE]) + struct.pack(">H", length)
        else:
            header = bytes([0x80 | opcode, 0xFF]) + struct.pack(">Q", length)
        masked = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
        self.socket.sendall(header + mask + masked)

    def _receive_frame(self, timeout: float) -> tuple[int, bytes]:
        self.socket.settimeout(timeout)
        first, second = self._read_exact(2)
        opcode = first & 0x0F
        masked = (second & 0x80) != 0
        length = second & 0x7F
        if length == 126:
            length = struct.unpack(">H", self._read_exact(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self._read_exact(8))[0]
        mask = self._read_exact(4) if masked else b""
        payload = self._read_exact(length)
        if masked:
            payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
        return opcode, payload

    def _read_exact(self, size: int) -> bytes:
        while len(self.buffer) < size:
            chunk = self.socket.recv(max(4096, size - len(self.buffer)))
            if not chunk:
                raise SmokeFailure(f"{self.label} WebSocket closed while reading")
            self.buffer += chunk
        result, self.buffer = self.buffer[:size], self.buffer[size:]
        return result


TestClient = Union[V1Client, V1WebSocketClient]


def require_failure(client: TestClient, response_type: str) -> dict[str, object]:
    payload = data(client.receive_type(response_type))
    if payload.get("success") is not False:
        raise SmokeFailure(f"{response_type} unexpectedly succeeded")
    return payload


def wrong_login(client: TestClient, username: str) -> dict[str, object]:
    client.send("LOGIN_REQ", {"username": username, "password": "wrong-password"})
    return require_failure(client, "LOGIN_RSP")


def run_server_scenario(
    server_path: Path,
    label: str,
    limits: dict[str, str],
    scenario: Callable[[int, list[TestClient]], None],
) -> str:
    base_port = find_port_range()
    output = ""
    failed = False

    with tempfile.TemporaryDirectory(prefix=f"chat-room-v1-auth-abuse-{label}-") as temp_path:
        temp = Path(temp_path)
        environment = os.environ.copy()
        environment.update(limits)
        environment["CHATROOM_DB_PATH"] = str(temp / f"{label}.db")
        environment["CHATROOM_DEVELOPER_KEY"] = "m1-auth-abuse-developer-key"
        process = subprocess.Popen(
            [str(server_path), "--port", str(base_port)],
            cwd=temp,
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        clients: list[TestClient] = []
        try:
            wait_for_server(process, base_port)
            scenario(base_port, clients)
        except Exception:
            failed = True
            raise
        finally:
            for client in clients:
                client.close()
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=3)
            output = process.stdout.read() if process.stdout else ""
            if failed and output:
                print(f"\n[V1AuthenticationAbuseTest] {label} server output:\n{output}",
                      file=sys.stderr)
    return output


def run_account_limit(server_path: Path) -> None:
    username = "limited_account"
    password = "account-recovery-password"

    def scenario(port: int, clients: list[TestClient]) -> None:
        registration = V1Client("127.0.0.1", port, "account-registration")
        clients.append(registration)
        register(registration, username, "Limited Account", password)

        for index in range(2):
            attacker = V1Client("127.0.0.1", port, f"account-attempt-{index}")
            clients.append(attacker)
            payload = wrong_login(attacker, username)
            if payload.get("error") == RATE_LIMIT_ERROR:
                raise SmokeFailure("account limit rejected an attempt before its boundary")

        blocked = V1Client("127.0.0.1", port, "account-blocked")
        clients.append(blocked)
        if wrong_login(blocked, username).get("error") != RATE_LIMIT_ERROR:
            raise SmokeFailure("account limit did not span independent connections")

        time.sleep(1.1)
        recovered = V1Client("127.0.0.1", port, "account-recovered")
        clients.append(recovered)
        login(recovered, username, password)

        for _ in range(2):
            recovered.send(
                "CHANGE_PASSWORD_REQ",
                {"oldPassword": "wrong-password", "newPassword": "replacement-password"},
            )
            payload = require_failure(recovered, "CHANGE_PASSWORD_RSP")
            if payload.get("error") == RATE_LIMIT_ERROR:
                raise SmokeFailure("password-change account limit rejected too early")
        recovered.send(
            "CHANGE_PASSWORD_REQ",
            {"oldPassword": "wrong-password", "newPassword": "replacement-password"},
        )
        if require_failure(recovered, "CHANGE_PASSWORD_RSP").get("error") != RATE_LIMIT_ERROR:
            raise SmokeFailure("password-change denial did not preserve its V1 response type")

    output = run_server_scenario(
        server_path,
        "account",
        {
            "CHATROOM_AUTH_WINDOW_MS": "1000",
            "CHATROOM_AUTH_GATEWAY_ATTEMPTS": "100",
            "CHATROOM_AUTH_IP_ATTEMPTS": "100",
            "CHATROOM_AUTH_ACCOUNT_ATTEMPTS": "2",
            "CHATROOM_AUTH_MAX_TRACKED_KEYS": "32",
        },
        scenario,
    )
    if "[AuthAbuse] denied operation=login dimension=account" not in output:
        raise SmokeFailure("account denial did not emit structured monitoring output")
    if password in output:
        raise SmokeFailure("authentication logs exposed a password")


def run_ip_limit(server_path: Path) -> None:
    def scenario(port: int, clients: list[TestClient]) -> None:
        first = V1Client("127.0.0.1", port, "ip-tcp-attempt")
        clients.append(first)
        if wrong_login(first, "missing_ip_0").get("error") == RATE_LIMIT_ERROR:
            raise SmokeFailure("IP limit rejected its first TCP attempt")

        second = V1WebSocketClient("127.0.0.1", port + 1, "ip-websocket-attempt")
        clients.append(second)
        if wrong_login(second, "missing_ip_1").get("error") == RATE_LIMIT_ERROR:
            raise SmokeFailure("IP limit rejected its second cross-transport attempt")

        blocked = V1WebSocketClient("127.0.0.1", port + 1, "ip-websocket-blocked")
        clients.append(blocked)
        if wrong_login(blocked, "missing_ip_2").get("error") != RATE_LIMIT_ERROR:
            raise SmokeFailure("IP limit did not span TCP/WebSocket connections")

        blocked_registration = V1WebSocketClient(
            "127.0.0.1", port + 1, "ip-register-blocked"
        )
        clients.append(blocked_registration)
        blocked_registration.send(
            "REGISTER_REQ",
            {
                "username": "blocked_register",
                "displayName": "Blocked Register",
                "password": "registration-password",
            },
        )
        if require_failure(blocked_registration, "REGISTER_RSP").get("error") != RATE_LIMIT_ERROR:
            raise SmokeFailure("registration denial did not preserve its V1 response type")

    output = run_server_scenario(
        server_path,
        "ip",
        {
            "CHATROOM_AUTH_WINDOW_MS": "1000",
            "CHATROOM_AUTH_GATEWAY_ATTEMPTS": "100",
            "CHATROOM_AUTH_IP_ATTEMPTS": "2",
            "CHATROOM_AUTH_ACCOUNT_ATTEMPTS": "100",
            "CHATROOM_AUTH_MAX_TRACKED_KEYS": "32",
        },
        scenario,
    )
    if "[AuthAbuse] denied operation=login dimension=ip" not in output:
        raise SmokeFailure("IP denial did not emit structured monitoring output")


def run_gateway_limit(server_path: Path) -> None:
    def scenario(port: int, clients: list[TestClient]) -> None:
        for index in range(2):
            attacker = V1Client("127.0.0.1", port, f"gateway-attempt-{index}")
            clients.append(attacker)
            payload = wrong_login(attacker, f"missing_gateway_{index}")
            if payload.get("error") == RATE_LIMIT_ERROR:
                raise SmokeFailure("gateway limit rejected an attempt before its boundary")

        blocked = V1Client("127.0.0.1", port, "gateway-blocked")
        clients.append(blocked)
        if wrong_login(blocked, "missing_gateway_2").get("error") != RATE_LIMIT_ERROR:
            raise SmokeFailure("gateway limit did not span accounts and connections")

    output = run_server_scenario(
        server_path,
        "gateway",
        {
            "CHATROOM_AUTH_WINDOW_MS": "1000",
            "CHATROOM_AUTH_GATEWAY_ATTEMPTS": "2",
            "CHATROOM_AUTH_IP_ATTEMPTS": "100",
            "CHATROOM_AUTH_ACCOUNT_ATTEMPTS": "100",
            "CHATROOM_AUTH_MAX_TRACKED_KEYS": "32",
        },
        scenario,
    )
    if "[AuthAbuse] denied operation=login dimension=gateway" not in output:
        raise SmokeFailure("gateway denial did not emit structured monitoring output")


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] != "--server":
        print("usage: v1_authentication_abuse_test.py --server PATH", file=sys.stderr)
        return 2
    server = Path(sys.argv[2]).resolve()
    if not server.is_file():
        print(f"server binary does not exist: {server}", file=sys.stderr)
        return 2

    try:
        run_account_limit(server)
        run_ip_limit(server)
        run_gateway_limit(server)
        print(
            "[V1AuthenticationAbuseTest] PASS: account, mixed-transport direct-peer "
            "IP, gateway, V1 responses, window recovery, and redacted monitoring "
            "are enforced"
        )
        return 0
    except (OSError, SmokeFailure, subprocess.SubprocessError, json.JSONDecodeError) as error:
        print(f"[V1AuthenticationAbuseTest] FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
