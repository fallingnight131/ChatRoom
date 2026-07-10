#!/usr/bin/env python3
"""Exercise V1 TCP frame, envelope, and per-connection rate limits."""

from __future__ import annotations

import base64
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

from v1_smoke_test import (
    SmokeFailure,
    V1Client,
    data,
    find_port_range,
    login,
    register,
    require_success,
    wait_for_server,
)


MAX_JSON_MESSAGE_BYTES = 16 * 1024 * 1024
MAX_MESSAGES_PER_SECOND = 60


def frame(payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + payload


def message(message_type: str, data: dict[str, object] | None = None) -> bytes:
    payload = json.dumps(
        {
            "type": message_type,
            "id": str(uuid.uuid4()),
            "timestamp": int(time.time() * 1000),
            "data": data or {},
        },
        separators=(",", ":"),
    ).encode("utf-8")
    return frame(payload)


def connect(port: int) -> socket.socket:
    return socket.create_connection(("127.0.0.1", port), timeout=3)


def require_disconnect(connection: socket.socket, label: str) -> None:
    connection.settimeout(3)
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        try:
            chunk = connection.recv(64 * 1024)
        except (ConnectionResetError, BrokenPipeError):
            return
        except TimeoutError as error:
            raise SmokeFailure(f"{label} connection remained open") from error
        if not chunk:
            return
    raise SmokeFailure(f"{label} connection remained open")


def receive_type(connection: socket.socket, expected_type: str) -> None:
    connection.settimeout(3)
    header = connection.recv(4)
    if len(header) != 4:
        raise SmokeFailure(f"missing response frame for {expected_type}")
    size = struct.unpack(">I", header)[0]
    payload = bytearray()
    while len(payload) < size:
        chunk = connection.recv(size - len(payload))
        if not chunk:
            raise SmokeFailure(f"connection closed before {expected_type}")
        payload.extend(chunk)
    response = json.loads(payload.decode("utf-8"))
    if response.get("type") != expected_type:
        raise SmokeFailure(f"expected {expected_type}, received {response.get('type')}")


def run_limits(server_path: Path) -> None:
    base_port = find_port_range()
    with tempfile.TemporaryDirectory(prefix="chat-room-v1-limits-") as temp_path:
        temp = Path(temp_path)
        environment = os.environ.copy()
        environment["CHATROOM_DB_PATH"] = str(temp / "transport-limits.db")
        environment["CHATROOM_DEVELOPER_KEY"] = "m1-transport-limits-key"
        process = subprocess.Popen(
            [str(server_path), "--port", str(base_port)],
            cwd=temp,
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )

        connections: list[socket.socket] = []
        clients: list[V1Client] = []
        try:
            wait_for_server(process, base_port)

            oversized = connect(base_port)
            connections.append(oversized)
            oversized.sendall(struct.pack(">I", MAX_JSON_MESSAGE_BYTES + 1))
            require_disconnect(oversized, "oversized frame")

            malformed = connect(base_port)
            connections.append(malformed)
            malformed.sendall(frame(b"{") * 3)
            require_disconnect(malformed, "malformed JSON")

            invalid_envelope = connect(base_port)
            connections.append(invalid_envelope)
            bad = frame(json.dumps({"type": "HEARTBEAT"}).encode("utf-8"))
            for _ in range(3):
                invalid_envelope.sendall(bad)
                time.sleep(0.05)
            require_disconnect(invalid_envelope, "invalid envelope")

            unknown = connect(base_port)
            connections.append(unknown)
            unknown.sendall(message("UNKNOWN_V1_TYPE"))
            unknown.sendall(message("HEARTBEAT"))
            receive_type(unknown, "HEARTBEAT_ACK")

            flooding = connect(base_port)
            connections.append(flooding)
            burst = b"".join(
                message("HEARTBEAT") for _ in range(MAX_MESSAGES_PER_SECOND + 1)
            )
            flooding.sendall(burst)
            require_disconnect(flooding, "message-rate flood")

            owner = V1Client("127.0.0.1", base_port, "limits-owner")
            slow = V1Client("127.0.0.1", base_port, "limits-slow-consumer")
            clients.extend([owner, slow])
            register(owner, "limits_owner", "Limits Owner", "limits-password")
            register(slow, "limits_slow", "Limits Slow", "limits-password")
            login(owner, "limits_owner", "limits-password")
            login(slow, "limits_slow", "limits-password")
            owner.send("CREATE_ROOM_REQ", {"roomName": "Transport Limits Room"})
            room_id = require_success(owner.receive_type("CREATE_ROOM_RSP")).get("roomId")
            if not isinstance(room_id, int) or room_id <= 0:
                raise SmokeFailure("slow-consumer setup did not create a room")
            slow.send("JOIN_ROOM_REQ", {"roomId": room_id})
            require_success(slow.receive_type("JOIN_ROOM_RSP"))

            large_bytes = b"x" * (8 * 1024 * 1024)
            owner.send(
                "FILE_SEND",
                {
                    "roomId": room_id,
                    "fileName": "backpressure.bin",
                    "fileSize": len(large_bytes),
                    "fileData": base64.b64encode(large_bytes).decode("ascii"),
                    "contentType": "file",
                },
            )
            notice = owner.receive_type(
                "FILE_NOTIFY",
                predicate=lambda item: data(item).get("fileName") == "backpressure.bin",
                timeout=10,
            )
            file_id = data(notice).get("fileId")
            if not isinstance(file_id, int) or file_id <= 0:
                raise SmokeFailure("slow-consumer setup did not persist the file")

            request = message("FILE_DOWNLOAD_REQ", {"fileId": file_id})
            slow.socket.sendall(request * 6)
            time.sleep(0.5)
            require_disconnect(slow.socket, "slow consumer")

            print(
                "[V1TransportLimitsTest] PASS: oversized frames, malformed JSON/envelopes, "
                "unknown types, message-rate floods, and slow consumers have deterministic behavior"
            )
        except Exception:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
            output = process.stdout.read() if process.stdout else ""
            if output:
                print("\n[V1TransportLimitsTest] server output:\n" + output, file=sys.stderr)
            raise
        finally:
            for client in clients:
                client.close()
            for connection in connections:
                connection.close()
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=3)


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] != "--server":
        print("usage: v1_transport_limits_test.py --server PATH", file=sys.stderr)
        return 2
    server = Path(sys.argv[2]).resolve()
    if not server.is_file():
        print(f"server binary does not exist: {server}", file=sys.stderr)
        return 2
    try:
        run_limits(server)
        return 0
    except (OSError, SmokeFailure, subprocess.SubprocessError, json.JSONDecodeError) as error:
        print(f"[V1TransportLimitsTest] FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
