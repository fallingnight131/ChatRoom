#!/usr/bin/env python3
"""Exercise critical V1 TCP flows against a real headless ChatServer binary."""

from __future__ import annotations

import argparse
import base64
import json
import os
import secrets
import socket
import struct
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path
from typing import Callable


class SmokeFailure(RuntimeError):
    pass


class V1Client:
    def __init__(self, host: str, port: int, label: str) -> None:
        self.label = label
        self.socket = socket.create_connection((host, port), timeout=5)
        self.pending: list[dict[str, object]] = []

    def close(self) -> None:
        try:
            self.socket.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.socket.close()

    def send(self, message_type: str, data: dict[str, object] | None = None) -> None:
        message = {
            "type": message_type,
            "id": str(uuid.uuid4()),
            "timestamp": int(time.time() * 1000),
            "data": data or {},
        }
        payload = json.dumps(message, separators=(",", ":"), ensure_ascii=False).encode()
        self.socket.sendall(struct.pack(">I", len(payload)) + payload)

    def receive_type(
        self,
        expected_type: str,
        timeout: float = 5,
        predicate: Callable[[dict[str, object]], bool] | None = None,
    ) -> dict[str, object]:
        predicate = predicate or (lambda _: True)
        for index, message in enumerate(self.pending):
            if message.get("type") == expected_type and predicate(message):
                return self.pending.pop(index)

        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            message = self._receive_frame(max(0.1, deadline - time.monotonic()))
            if message.get("type") == expected_type and predicate(message):
                return message
            self.pending.append(message)
        observed = [str(message.get("type")) for message in self.pending]
        raise SmokeFailure(
            f"{self.label} timed out waiting for {expected_type}; pending={observed}"
        )

    def _receive_frame(self, timeout: float) -> dict[str, object]:
        self.socket.settimeout(timeout)
        header = self._read_exact(4)
        length = struct.unpack(">I", header)[0]
        if length <= 0 or length > 50 * 1024 * 1024:
            raise SmokeFailure(f"{self.label} received invalid frame length {length}")
        payload = self._read_exact(length)
        message = json.loads(payload.decode("utf-8"))
        if not isinstance(message, dict):
            raise SmokeFailure(f"{self.label} received a non-object JSON frame")
        return message

    def _read_exact(self, size: int) -> bytes:
        chunks: list[bytes] = []
        remaining = size
        while remaining:
            chunk = self.socket.recv(remaining)
            if not chunk:
                raise SmokeFailure(f"{self.label} connection closed while reading")
            chunks.append(chunk)
            remaining -= len(chunk)
        return b"".join(chunks)


def data(message: dict[str, object]) -> dict[str, object]:
    value = message.get("data")
    if not isinstance(value, dict):
        raise SmokeFailure(f"message {message.get('type')} has no data object")
    return value


def require_success(message: dict[str, object]) -> dict[str, object]:
    payload = data(message)
    if payload.get("success") is not True:
        raise SmokeFailure(
            f"{message.get('type')} failed: {payload.get('error', 'unknown error')}"
        )
    return payload


def find_port_range() -> int:
    for _ in range(100):
        base = 20000 + secrets.randbelow(20000)
        sockets: list[socket.socket] = []
        try:
            for port in (base, base + 1, base + 2):
                candidate = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                candidate.bind(("127.0.0.1", port))
                sockets.append(candidate)
            return base
        except OSError:
            continue
        finally:
            for candidate in sockets:
                candidate.close()
    raise SmokeFailure("could not reserve three consecutive local ports")


def wait_for_server(process: subprocess.Popen[str], port: int) -> None:
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise SmokeFailure(f"server exited during startup with {process.returncode}")
        try:
            connection = socket.create_connection(("127.0.0.1", port), timeout=0.2)
            connection.close()
            return
        except OSError:
            time.sleep(0.05)
    raise SmokeFailure("server did not start listening within 10 seconds")


def register(client: V1Client, username: str, display_name: str, password: str) -> None:
    client.send(
        "REGISTER_REQ",
        {"username": username, "displayName": display_name, "password": password},
    )
    require_success(client.receive_type("REGISTER_RSP"))


def login(client: V1Client, username: str, password: str) -> dict[str, object]:
    client.send("LOGIN_REQ", {"username": username, "password": password})
    payload = require_success(client.receive_type("LOGIN_RSP"))
    if payload.get("username") != username:
        raise SmokeFailure(f"login returned wrong username for {username}")
    return payload


def run_smoke(server_path: Path) -> None:
    password = "m0-test-password"
    alice_name = f"alice_{secrets.token_hex(3)}"
    bob_name = f"bob_{secrets.token_hex(3)}"
    token = f"m0-room-message-{uuid.uuid4()}"
    second_token = f"m0-reconnect-message-{uuid.uuid4()}"
    direct_token = f"m0-direct-message-{uuid.uuid4()}"
    base_port = find_port_range()

    with tempfile.TemporaryDirectory(prefix="chat-room-v1-smoke-") as temp_path:
        temp = Path(temp_path)
        environment = os.environ.copy()
        environment["CHATROOM_DB_PATH"] = str(temp / "smoke.db")
        environment["CHATROOM_DEVELOPER_KEY"] = "m0-smoke-developer-key"
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
            alice = V1Client("127.0.0.1", base_port, "alice")
            bob = V1Client("127.0.0.1", base_port, "bob")
            clients.extend([alice, bob])

            register(alice, alice_name, "Alice M0", password)
            register(bob, bob_name, "Bob M0", password)
            login(alice, alice_name, password)
            login(bob, bob_name, password)

            alice.send("CREATE_ROOM_REQ", {"roomName": "M0 Smoke Room"})
            create = require_success(alice.receive_type("CREATE_ROOM_RSP"))
            room_id = create.get("roomId")
            if not isinstance(room_id, int) or room_id <= 0:
                raise SmokeFailure(f"invalid room id: {room_id}")

            bob.send("JOIN_ROOM_REQ", {"roomId": room_id})
            join = require_success(bob.receive_type("JOIN_ROOM_RSP"))
            if join.get("newJoin") is not True:
                raise SmokeFailure("Bob's first room join was not marked newJoin")

            alice.send(
                "CHAT_MSG",
                {"roomId": room_id, "content": token, "contentType": "text"},
            )
            match_token = lambda message: data(message).get("content") == token
            alice_message = alice.receive_type("CHAT_MSG", predicate=match_token)
            bob_message = bob.receive_type("CHAT_MSG", predicate=match_token)
            message_id = data(alice_message).get("id")
            if not isinstance(message_id, int) or message_id <= 0:
                raise SmokeFailure(f"invalid persisted message id: {message_id}")
            if data(bob_message).get("id") != message_id:
                raise SmokeFailure("room recipients observed different message IDs")
            if data(bob_message).get("sender") != alice_name:
                raise SmokeFailure("server did not enforce authenticated sender identity")

            bob.send("HISTORY_REQ", {"roomId": room_id, "count": 20})
            history = data(bob.receive_type("HISTORY_RSP"))
            messages = history.get("messages")
            if not isinstance(messages, list) or not any(
                isinstance(message, dict) and message.get("content") == token
                for message in messages
            ):
                raise SmokeFailure("persisted room message was absent from history")

            alice.send(
                "FILE_SEND",
                {
                    "roomId": room_id,
                    "fileName": "m0-smoke.txt",
                    "fileSize": 2,
                    "fileData": base64.b64encode(b"m0").decode("ascii"),
                    "contentType": "file",
                },
            )
            match_file = lambda message: data(message).get("fileName") == "m0-smoke.txt"
            file_notice = bob.receive_type("FILE_NOTIFY", predicate=match_file)
            file_data = data(file_notice)
            if not isinstance(file_data.get("fileId"), int) or file_data.get("fileSize") != 2:
                raise SmokeFailure("file notification metadata was incomplete")

            bob.close()
            clients.remove(bob)
            time.sleep(0.1)

            bob_reconnected = V1Client("127.0.0.1", base_port, "bob-reconnected")
            clients.append(bob_reconnected)
            login(bob_reconnected, bob_name, password)
            bob_reconnected.send("ROOM_LIST_REQ")
            rooms = data(bob_reconnected.receive_type("ROOM_LIST_RSP")).get("rooms")
            if not isinstance(rooms, list) or not any(
                isinstance(room, dict) and room.get("roomId") == room_id for room in rooms
            ):
                raise SmokeFailure("room membership did not survive reconnect")

            alice.send(
                "CHAT_MSG",
                {"roomId": room_id, "content": second_token, "contentType": "text"},
            )
            match_second = lambda message: data(message).get("content") == second_token
            bob_reconnected.receive_type("CHAT_MSG", predicate=match_second)

            alice.send("RECALL_REQ", {"roomId": room_id, "messageId": message_id})
            require_success(alice.receive_type("RECALL_RSP"))
            recall_match = lambda message: data(message).get("messageId") == message_id
            bob_reconnected.receive_type("RECALL_NOTIFY", predicate=recall_match)

            alice.send("FRIEND_REQUEST_REQ", {"username": bob_name})
            require_success(alice.receive_type("FRIEND_REQUEST_RSP"))
            request_match = lambda message: data(message).get("fromUsername") == alice_name
            bob_reconnected.receive_type("FRIEND_REQUEST_NOTIFY", predicate=request_match)

            bob_reconnected.send("FRIEND_PENDING_REQ")
            pending = data(
                bob_reconnected.receive_type("FRIEND_PENDING_RSP")
            ).get("requests")
            if not isinstance(pending, list):
                raise SmokeFailure("pending friend response did not contain a request list")
            request = next(
                (
                    item
                    for item in pending
                    if isinstance(item, dict) and item.get("fromUsername") == alice_name
                ),
                None,
            )
            if not isinstance(request, dict) or not isinstance(request.get("requestId"), int):
                raise SmokeFailure("Alice's friend request was absent from Bob's pending list")

            bob_reconnected.send(
                "FRIEND_ACCEPT_REQ",
                {"requestId": request["requestId"], "fromUsername": alice_name},
            )
            require_success(bob_reconnected.receive_type("FRIEND_ACCEPT_RSP"))
            accept_match = lambda message: data(message).get("acceptedBy") == bob_name
            alice.receive_type("FRIEND_ACCEPT_NOTIFY", predicate=accept_match)

            alice.send("FRIEND_LIST_REQ")
            alice_friends = data(alice.receive_type("FRIEND_LIST_RSP")).get("friends")
            if not isinstance(alice_friends, list) or not any(
                isinstance(friend, dict) and friend.get("username") == bob_name
                for friend in alice_friends
            ):
                raise SmokeFailure("Bob was absent from Alice's accepted friend list")

            alice.send(
                "FRIEND_CHAT_MSG",
                {
                    "friendUsername": bob_name,
                    "content": direct_token,
                    "contentType": "text",
                },
            )
            direct_match = lambda message: data(message).get("content") == direct_token
            alice_direct = alice.receive_type("FRIEND_CHAT_MSG", predicate=direct_match)
            bob_direct = bob_reconnected.receive_type(
                "FRIEND_CHAT_MSG", predicate=direct_match
            )
            direct_message_id = data(alice_direct).get("id")
            if not isinstance(direct_message_id, int) or direct_message_id <= 0:
                raise SmokeFailure("direct message did not receive a persisted ID")
            if data(bob_direct).get("id") != direct_message_id:
                raise SmokeFailure("direct-message participants observed different IDs")

            bob_reconnected.send(
                "FRIEND_HISTORY_REQ", {"friendUsername": alice_name, "count": 20}
            )
            direct_history = data(
                bob_reconnected.receive_type("FRIEND_HISTORY_RSP")
            ).get("messages")
            if not isinstance(direct_history, list) or not any(
                isinstance(message, dict) and message.get("content") == direct_token
                for message in direct_history
            ):
                raise SmokeFailure("persisted direct message was absent from history")

            alice.send(
                "FRIEND_RECALL_REQ",
                {"messageId": direct_message_id, "friendUsername": bob_name},
            )
            require_success(alice.receive_type("FRIEND_RECALL_RSP"))
            direct_recall_match = (
                lambda message: data(message).get("messageId") == direct_message_id
            )
            bob_reconnected.receive_type(
                "FRIEND_RECALL_NOTIFY", predicate=direct_recall_match
            )

            print(
                "[V1SmokeTest] PASS: register, login, room join, message fan-out, "
                "history, file metadata, reconnect, recall, friendship, and direct chat"
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
                print("\n[V1SmokeTest] server output:\n" + output, file=sys.stderr)
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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    server = args.server.resolve()
    if not server.is_file():
        print(f"server binary does not exist: {server}", file=sys.stderr)
        return 2
    try:
        run_smoke(server)
        return 0
    except (OSError, SmokeFailure, subprocess.SubprocessError, json.JSONDecodeError) as error:
        print(f"[V1SmokeTest] FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
