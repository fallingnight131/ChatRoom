#!/usr/bin/env python3
"""Verify V1 room and attachment authorization against a real server."""

from __future__ import annotations

import base64
import json
import os
import secrets
import socket
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


def require_denied(message: dict[str, object]) -> dict[str, object]:
    payload = data(message)
    if payload.get("success") is not False:
        raise SmokeFailure(f"{message.get('type')} unexpectedly succeeded")
    return payload


def http_status(port: int, file_id: int, token: str) -> int:
    connection = socket.create_connection(("127.0.0.1", port), timeout=5)
    try:
        request = (
            f"GET /api/download/{file_id}?token={token} HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n"
        ).encode("ascii")
        connection.sendall(request)
        response = bytearray()
        while b"\r\n" not in response:
            chunk = connection.recv(4096)
            if not chunk:
                break
            response.extend(chunk)
        first_line = bytes(response).split(b"\r\n", 1)[0].decode("ascii")
        parts = first_line.split()
        if len(parts) < 2 or not parts[1].isdigit():
            raise SmokeFailure(f"invalid HTTP response: {first_line}")
        return int(parts[1])
    finally:
        connection.close()


def run_authorization(server_path: Path) -> None:
    password = "m1-authorization-password"
    suffix = secrets.token_hex(3)
    alice_name = f"auth_alice_{suffix}"
    bob_name = f"auth_bob_{suffix}"
    outsider_name = f"auth_outsider_{suffix}"
    seed_token = f"authorized-{uuid.uuid4()}"
    denied_token = f"denied-{uuid.uuid4()}"
    base_port = find_port_range()

    with tempfile.TemporaryDirectory(prefix="chat-room-v1-authz-") as temp_path:
        temp = Path(temp_path)
        environment = os.environ.copy()
        environment["CHATROOM_DB_PATH"] = str(temp / "authorization.db")
        environment["CHATROOM_DEVELOPER_KEY"] = "m1-authorization-developer-key"
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
            alice = V1Client("127.0.0.1", base_port, "authorization-alice")
            bob = V1Client("127.0.0.1", base_port, "authorization-bob")
            outsider = V1Client("127.0.0.1", base_port, "authorization-outsider")
            clients.extend([alice, bob, outsider])

            register(alice, alice_name, "Authorization Alice", password)
            register(bob, bob_name, "Authorization Bob", password)
            register(outsider, outsider_name, "Authorization Outsider", password)
            alice_login = login(alice, alice_name, password)
            login(bob, bob_name, password)
            outsider_login = login(outsider, outsider_name, password)

            alice.send("CREATE_ROOM_REQ", {"roomName": "M1 Authorization Room"})
            room_id = require_success(alice.receive_type("CREATE_ROOM_RSP")).get("roomId")
            if not isinstance(room_id, int) or room_id <= 0:
                raise SmokeFailure(f"invalid room id: {room_id}")
            bob.send("JOIN_ROOM_REQ", {"roomId": room_id})
            require_success(bob.receive_type("JOIN_ROOM_RSP"))

            outsider.send("LEAVE_ROOM", {"roomId": room_id})
            require_denied(outsider.receive_type("LEAVE_ROOM_RSP"))
            alice.send(
                "SET_ADMIN_REQ",
                {"roomId": room_id, "username": outsider_name, "isAdmin": True},
            )
            require_denied(alice.receive_type("SET_ADMIN_RSP"))
            outsider.send(
                "ROOM_SETTINGS_REQ",
                {
                    "roomId": room_id,
                    "maxMembers": 60,
                    "developerKey": "m1-authorization-developer-key",
                },
            )
            require_denied(outsider.receive_type("ROOM_SETTINGS_RSP"))
            bob.send(
                "ROOM_SETTINGS_REQ",
                {
                    "roomId": room_id,
                    "maxMembers": 60,
                    "developerKey": "m1-authorization-developer-key",
                },
            )
            require_denied(bob.receive_type("ROOM_SETTINGS_RSP"))
            alice.send(
                "ROOM_SETTINGS_REQ",
                {
                    "roomId": room_id,
                    "maxMembers": 60,
                    "developerKey": "m1-authorization-developer-key",
                },
            )
            require_success(alice.receive_type("ROOM_SETTINGS_RSP"))

            alice.send(
                "CHAT_MSG",
                {"roomId": room_id, "content": seed_token, "contentType": "text"},
            )
            seed_match = lambda message: data(message).get("content") == seed_token
            alice_message = alice.receive_type("CHAT_MSG", predicate=seed_match)
            bob.receive_type("CHAT_MSG", predicate=seed_match)
            message_id = data(alice_message).get("id")
            if not isinstance(message_id, int) or message_id <= 0:
                raise SmokeFailure("authorized message was not persisted")

            outsider.send("USER_LIST_REQ", {"roomId": room_id})
            require_denied(outsider.receive_type("USER_LIST_RSP"))
            outsider.send("HISTORY_REQ", {"roomId": room_id, "count": 20})
            denied_history = require_denied(outsider.receive_type("HISTORY_RSP"))
            if "messages" in denied_history:
                raise SmokeFailure("denied history response leaked message data")

            outsider.send(
                "CHAT_MSG",
                {"roomId": room_id, "content": denied_token, "contentType": "text"},
            )
            time.sleep(0.1)
            bob.send("HISTORY_REQ", {"roomId": room_id, "count": 50})
            messages = require_success(bob.receive_type("HISTORY_RSP")).get("messages")
            if not isinstance(messages, list):
                raise SmokeFailure("authorized history response omitted messages")
            if any(
                isinstance(message, dict) and message.get("content") == denied_token
                for message in messages
            ):
                raise SmokeFailure("outsider room message reached durable history")

            alice.send("CREATE_ROOM_REQ", {"roomName": "M1 Recall Isolation Room"})
            other_room = require_success(alice.receive_type("CREATE_ROOM_RSP")).get("roomId")
            if not isinstance(other_room, int) or other_room <= 0:
                raise SmokeFailure("second room creation failed")
            alice.send("RECALL_REQ", {"roomId": other_room, "messageId": message_id})
            require_denied(alice.receive_type("RECALL_RSP"))
            bob.send("HISTORY_REQ", {"roomId": room_id, "count": 50})
            messages = require_success(bob.receive_type("HISTORY_RSP")).get("messages")
            if not isinstance(messages, list) or not any(
                isinstance(message, dict)
                and message.get("id") == message_id
                and message.get("content") == seed_token
                for message in messages
            ):
                raise SmokeFailure("cross-room recall mutated the original message")

            alice.send(
                "FILE_SEND",
                {
                    "roomId": room_id,
                    "fileName": "authorized.txt",
                    "fileSize": 2,
                    "fileData": base64.b64encode(b"ok").decode("ascii"),
                    "contentType": "file",
                },
            )
            file_match = lambda message: data(message).get("fileName") == "authorized.txt"
            file_notice = bob.receive_type("FILE_NOTIFY", predicate=file_match)
            file_id = data(file_notice).get("fileId")
            if not isinstance(file_id, int) or file_id <= 0:
                raise SmokeFailure("authorized file did not receive an id")

            outsider.send(
                "FILE_SEND",
                {
                    "roomId": room_id,
                    "fileName": "denied.txt",
                    "fileSize": 2,
                    "fileData": base64.b64encode(b"no").decode("ascii"),
                    "contentType": "file",
                },
            )
            require_denied(outsider.receive_type("FILE_NOTIFY"))

            outsider.send(
                "FILE_UPLOAD_START",
                {"roomId": room_id, "fileName": "denied-large.txt", "fileSize": 2},
            )
            require_denied(outsider.receive_type("FILE_UPLOAD_START_RSP"))

            alice.send(
                "FILE_UPLOAD_START",
                {"roomId": room_id, "fileName": "owned-upload.txt", "fileSize": 2},
            )
            upload_id = require_success(
                alice.receive_type("FILE_UPLOAD_START_RSP")
            ).get("uploadId")
            if not isinstance(upload_id, str) or not upload_id:
                raise SmokeFailure("authorized upload start omitted upload id")
            outsider.send(
                "FILE_UPLOAD_CHUNK",
                {"uploadId": upload_id, "chunkData": base64.b64encode(b"xx").decode("ascii")},
            )
            require_denied(outsider.receive_type("FILE_UPLOAD_CHUNK_RSP"))
            alice.send(
                "FILE_UPLOAD_CHUNK",
                {"uploadId": upload_id, "chunkData": base64.b64encode(b"ok").decode("ascii")},
            )
            require_success(alice.receive_type("FILE_UPLOAD_CHUNK_RSP"))
            alice.send("FILE_UPLOAD_END", {"uploadId": upload_id})
            owned_match = lambda message: data(message).get("fileName") == "owned-upload.txt"
            bob.receive_type("FILE_NOTIFY", predicate=owned_match)

            outsider.send("FILE_DOWNLOAD_REQ", {"fileId": file_id})
            require_denied(outsider.receive_type("FILE_DOWNLOAD_RSP"))
            outsider.send(
                "FILE_DOWNLOAD_CHUNK_REQ",
                {"fileId": file_id, "offset": 0, "chunkSize": 2},
            )
            require_denied(outsider.receive_type("FILE_DOWNLOAD_CHUNK_RSP"))

            alice.send("FILE_DOWNLOAD_REQ", {"fileId": file_id})
            downloaded = require_success(alice.receive_type("FILE_DOWNLOAD_RSP"))
            if base64.b64decode(str(downloaded.get("fileData", ""))) != b"ok":
                raise SmokeFailure("authorized TCP file download returned wrong bytes")

            alice_token = alice_login.get("fileToken")
            outsider_token = outsider_login.get("fileToken")
            if not isinstance(alice_token, str) or not isinstance(outsider_token, str):
                raise SmokeFailure("login response omitted HTTP file token")
            if http_status(base_port + 2, file_id, outsider_token) != 403:
                raise SmokeFailure("outsider HTTP file download was not forbidden")
            if http_status(base_port + 2, file_id, alice_token) != 200:
                raise SmokeFailure("authorized HTTP file download did not succeed")

            alice.send("FRIEND_REQUEST_REQ", {"username": bob_name})
            require_success(alice.receive_type("FRIEND_REQUEST_RSP"))
            bob.receive_type(
                "FRIEND_REQUEST_NOTIFY",
                predicate=lambda message: data(message).get("fromUsername") == alice_name,
            )
            bob.send("FRIEND_PENDING_REQ")
            pending = data(bob.receive_type("FRIEND_PENDING_RSP")).get("requests")
            if not isinstance(pending, list):
                raise SmokeFailure("pending friend response omitted request list")
            request = next(
                (
                    item
                    for item in pending
                    if isinstance(item, dict) and item.get("fromUsername") == alice_name
                ),
                None,
            )
            if not isinstance(request, dict) or not isinstance(request.get("requestId"), int):
                raise SmokeFailure("friend request was absent from pending list")
            bob.send(
                "FRIEND_ACCEPT_REQ",
                {"requestId": request["requestId"], "fromUsername": outsider_name},
            )
            require_success(bob.receive_type("FRIEND_ACCEPT_RSP"))
            alice.receive_type(
                "FRIEND_ACCEPT_NOTIFY",
                predicate=lambda message: data(message).get("acceptedBy") == bob_name,
            )
            outsider.send("HEARTBEAT")
            outsider.receive_type("HEARTBEAT_ACK")
            if any(message.get("type") == "FRIEND_ACCEPT_NOTIFY" for message in outsider.pending):
                raise SmokeFailure("friend acceptance trusted a client-supplied notification target")

            alice.send(
                "FRIEND_FILE_SEND",
                {
                    "friendUsername": bob_name,
                    "fileName": "friend-authorized.txt",
                    "fileSize": 2,
                    "fileData": base64.b64encode(b"dm").decode("ascii"),
                },
            )
            friend_file_match = (
                lambda message: data(message).get("fileName") == "friend-authorized.txt"
            )
            alice_friend_file = alice.receive_type(
                "FRIEND_FILE_NOTIFY", predicate=friend_file_match
            )
            bob.receive_type("FRIEND_FILE_NOTIFY", predicate=friend_file_match)
            friend_file_id = data(alice_friend_file).get("fileId")
            if not isinstance(friend_file_id, int) or friend_file_id >= 0:
                raise SmokeFailure("friend file did not use a negative protocol id")
            outsider.send("FILE_DOWNLOAD_REQ", {"fileId": friend_file_id})
            require_denied(outsider.receive_type("FILE_DOWNLOAD_RSP"))
            bob.send("FILE_DOWNLOAD_REQ", {"fileId": friend_file_id})
            friend_download = require_success(bob.receive_type("FILE_DOWNLOAD_RSP"))
            if base64.b64decode(str(friend_download.get("fileData", ""))) != b"dm":
                raise SmokeFailure("friend participant received wrong file bytes")

            direct_token = f"direct-{uuid.uuid4()}"
            alice.send(
                "FRIEND_CHAT_MSG",
                {
                    "friendUsername": bob_name,
                    "content": direct_token,
                    "contentType": "text",
                },
            )
            direct_match = lambda message: data(message).get("content") == direct_token
            direct_message = alice.receive_type("FRIEND_CHAT_MSG", predicate=direct_match)
            bob.receive_type("FRIEND_CHAT_MSG", predicate=direct_match)
            direct_message_id = data(direct_message).get("id")
            if not isinstance(direct_message_id, int) or direct_message_id <= 0:
                raise SmokeFailure("direct message was not persisted")
            alice.send(
                "FRIEND_RECALL_REQ",
                {"messageId": direct_message_id, "friendUsername": outsider_name},
            )
            require_success(alice.receive_type("FRIEND_RECALL_RSP"))
            bob.receive_type(
                "FRIEND_RECALL_NOTIFY",
                predicate=lambda message: data(message).get("messageId") == direct_message_id,
            )
            outsider.send("HEARTBEAT")
            outsider.receive_type("HEARTBEAT_ACK")
            if any(message.get("type") == "FRIEND_RECALL_NOTIFY" for message in outsider.pending):
                raise SmokeFailure("direct recall trusted a client-supplied notification target")

            print(
                "[V1AuthorizationTest] PASS: room data, message writes, recall, "
                "file transfer, upload ownership, friend notifications, and HTTP downloads "
                "are resource-authorized"
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
                print("\n[V1AuthorizationTest] server output:\n" + output, file=sys.stderr)
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
        print("usage: v1_authorization_test.py --server PATH", file=sys.stderr)
        return 2
    server = Path(sys.argv[2]).resolve()
    if not server.is_file():
        print(f"server binary does not exist: {server}", file=sys.stderr)
        return 2
    try:
        run_authorization(server)
        return 0
    except (OSError, SmokeFailure, subprocess.SubprocessError, json.JSONDecodeError) as error:
        print(f"[V1AuthorizationTest] FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
