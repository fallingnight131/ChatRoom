#!/usr/bin/env python3
"""Verify field-level V1 input and upload-size invariants."""

from __future__ import annotations

import base64
import json
import os
import secrets
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
from v1_transport_limits_test import require_disconnect


def require_denied(message: dict[str, object]) -> dict[str, object]:
    payload = data(message)
    if payload.get("success") is not False:
        raise SmokeFailure(f"{message.get('type')} unexpectedly succeeded")
    return payload


def run_validation(server_path: Path) -> None:
    base_port = find_port_range()
    suffix = secrets.token_hex(3)
    password = "input-validation-password"

    with tempfile.TemporaryDirectory(prefix="chat-room-v1-input-") as temp_path:
        temp = Path(temp_path)
        environment = os.environ.copy()
        environment["CHATROOM_DB_PATH"] = str(temp / "input-validation.db")
        environment["CHATROOM_DEVELOPER_KEY"] = "m1-input-validation-key"
        process = subprocess.Popen(
            [str(server_path), "--port", str(base_port)],
            cwd=temp,
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )

        clients: list[V1Client] = []
        try:
            wait_for_server(process, base_port)

            auth_flood = V1Client("127.0.0.1", base_port, "auth-work-flood")
            clients.append(auth_flood)
            for _ in range(5):
                auth_flood.send(
                    "LOGIN_REQ", {"username": "missing_user", "password": "wrong-password"}
                )
                require_denied(auth_flood.receive_type("LOGIN_RSP"))
            auth_flood.send(
                "LOGIN_REQ", {"username": "missing_user", "password": "wrong-password"}
            )
            require_disconnect(auth_flood.socket, "authentication work flood")

            invalid_registration = V1Client(
                "127.0.0.1", base_port, "invalid-registration"
            )
            clients.append(invalid_registration)
            invalid_registration.send(
                "REGISTER_REQ",
                {
                    "username": f"invalid_{suffix}",
                    "displayName": "Invalid Password",
                    "password": "x" * 1025,
                },
            )
            require_denied(invalid_registration.receive_type("REGISTER_RSP"))

            alice_name = f"input_alice_{suffix}"
            alice = V1Client("127.0.0.1", base_port, "input-alice")
            clients.append(alice)
            register(alice, alice_name, "Input Alice", password)
            login(alice, alice_name, password)
            alice.send("CREATE_ROOM_REQ", {"roomName": "Input Validation Room"})
            room_id = require_success(alice.receive_type("CREATE_ROOM_RSP")).get("roomId")
            if not isinstance(room_id, int) or room_id <= 0:
                raise SmokeFailure("input validation room creation failed")

            oversized_token = f"oversized-{uuid.uuid4()}"
            alice.send(
                "CHAT_MSG",
                {
                    "roomId": room_id,
                    "content": oversized_token + ("x" * (64 * 1024)),
                    "contentType": "text",
                },
            )
            alice.send(
                "CHAT_MSG",
                {"roomId": room_id, "content": "unsupported", "contentType": "script"},
            )
            time.sleep(0.1)
            alice.send("HISTORY_REQ", {"roomId": room_id, "count": 1000000})
            messages = require_success(alice.receive_type("HISTORY_RSP")).get("messages")
            if not isinstance(messages, list) or len(messages) > 100:
                raise SmokeFailure("history count was not bounded")
            if any(
                isinstance(item, dict)
                and (
                    str(item.get("content", "")).startswith(oversized_token)
                    or item.get("content") == "unsupported"
                )
                for item in messages
            ):
                raise SmokeFailure("invalid message content reached durable history")

            for file_name, declared_size, raw in (
                ("size-mismatch.txt", 1, b"two"),
                ("../path-traversal.txt", 2, b"ok"),
            ):
                alice.send(
                    "FILE_SEND",
                    {
                        "roomId": room_id,
                        "fileName": file_name,
                        "fileSize": declared_size,
                        "fileData": base64.b64encode(raw).decode("ascii"),
                    },
                )
                require_denied(alice.receive_type("FILE_NOTIFY"))

            alice.send(
                "FILE_UPLOAD_START",
                {"roomId": room_id, "fileName": "bounded-upload.txt", "fileSize": 2},
            )
            upload_id = require_success(
                alice.receive_type("FILE_UPLOAD_START_RSP")
            ).get("uploadId")
            if not isinstance(upload_id, str):
                raise SmokeFailure("valid upload start omitted upload id")
            alice.send(
                "FILE_UPLOAD_CHUNK",
                {"uploadId": upload_id, "chunkData": base64.b64encode(b"too").decode("ascii")},
            )
            require_denied(alice.receive_type("FILE_UPLOAD_CHUNK_RSP"))
            alice.send(
                "FILE_UPLOAD_CHUNK",
                {"uploadId": upload_id, "chunkData": base64.b64encode(b"ok").decode("ascii")},
            )
            require_success(alice.receive_type("FILE_UPLOAD_CHUNK_RSP"))
            alice.send("FILE_UPLOAD_END", {"uploadId": upload_id})
            alice.receive_type(
                "FILE_NOTIFY",
                predicate=lambda item: data(item).get("fileName") == "bounded-upload.txt",
            )

            alice.send(
                "FILE_UPLOAD_START",
                {"roomId": room_id, "fileName": "incomplete-upload.txt", "fileSize": 3},
            )
            incomplete_id = require_success(
                alice.receive_type("FILE_UPLOAD_START_RSP")
            ).get("uploadId")
            if not isinstance(incomplete_id, str):
                raise SmokeFailure("incomplete upload start omitted upload id")
            alice.send(
                "FILE_UPLOAD_CHUNK",
                {
                    "uploadId": incomplete_id,
                    "chunkData": base64.b64encode(b"no").decode("ascii"),
                },
            )
            require_success(alice.receive_type("FILE_UPLOAD_CHUNK_RSP"))
            alice.send("FILE_UPLOAD_END", {"uploadId": incomplete_id})
            time.sleep(0.1)
            alice.send("HISTORY_REQ", {"roomId": room_id, "count": 100})
            messages = require_success(alice.receive_type("HISTORY_RSP")).get("messages")
            if not isinstance(messages, list) or any(
                isinstance(item, dict) and item.get("fileName") == "incomplete-upload.txt"
                for item in messages
            ):
                raise SmokeFailure("incomplete upload was finalized")

            print(
                "[V1InputValidationTest] PASS: password/message/history/file-name, "
                "declared-size, chunk, completion, and auth-work limits are enforced"
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
                print("\n[V1InputValidationTest] server output:\n" + output, file=sys.stderr)
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


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] != "--server":
        print("usage: v1_input_validation_test.py --server PATH", file=sys.stderr)
        return 2
    server = Path(sys.argv[2]).resolve()
    if not server.is_file():
        print(f"server binary does not exist: {server}", file=sys.stderr)
        return 2
    try:
        run_validation(server)
        return 0
    except (OSError, SmokeFailure, subprocess.SubprocessError, json.JSONDecodeError) as error:
        print(f"[V1InputValidationTest] FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
