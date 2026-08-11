#!/usr/bin/env python3
"""Verify replayable, idempotent room and direct-message recall."""

from __future__ import annotations

import argparse
import sqlite3
import tempfile
import uuid
from pathlib import Path

from v1_room_message_reliability_test import send, start_server, stop_server
from v1_smoke_test import (
    SmokeFailure,
    V1Client,
    data,
    find_port_range,
    login,
    register,
    require_success,
)


def assert_replayed(message: dict[str, object], original_sequence: int,
                    mutation_sequence: int) -> None:
    if (
        message.get("sequence") != original_sequence
        or message.get("mutationSequence") != mutation_sequence
        or message.get("syncSequence") != mutation_sequence
        or message.get("recalled") is not True
    ):
        raise SmokeFailure(f"invalid replayed recall state: {message}")


def run_test(server: Path) -> None:
    port = find_port_range()
    suffix = uuid.uuid4().hex[:7]
    alice_name, bob_name = f"ra_{suffix}", f"rb_{suffix}"
    password = "recall-replay-password"

    with tempfile.TemporaryDirectory(prefix="chat-recall-replay-") as temp_name:
        directory = Path(temp_name)
        database = directory / "recall-replay.db"
        process = start_server(server, directory, database, port)
        clients: list[V1Client] = []
        try:
            alice = V1Client("127.0.0.1", port, "recall-alice")
            bob = V1Client("127.0.0.1", port, "recall-bob")
            clients.extend([alice, bob])
            register(alice, alice_name, "Recall Alice", password)
            register(bob, bob_name, "Recall Bob", password)
            login(alice, alice_name, password)
            login(bob, bob_name, password)

            alice.send("CREATE_ROOM_REQ", {"roomName": "Recall Replay Room"})
            room_id = require_success(alice.receive_type("CREATE_ROOM_RSP")).get("roomId")
            if not isinstance(room_id, int):
                raise SmokeFailure("room creation omitted room id")
            bob.send("JOIN_ROOM_REQ", {"roomId": room_id, "password": ""})
            require_success(bob.receive_type("JOIN_ROOM_RSP"))

            room_client_id = str(uuid.uuid4())
            send(alice, "CHAT_MSG", {
                "roomId": room_id,
                "content": "room recall target",
                "contentType": "text",
                "clientMessageId": room_client_id,
            })
            room_ack = require_success(alice.receive_type("CHAT_SEND_RSP"))
            room_message_id = room_ack.get("id")
            alice.receive_type("CHAT_MSG", predicate=lambda m: data(m).get("id") == room_message_id)
            bob.receive_type("CHAT_MSG", predicate=lambda m: data(m).get("id") == room_message_id)

            alice.send("RECALL_REQ", {"roomId": room_id, "messageId": room_message_id})
            room_recall = require_success(alice.receive_type("RECALL_RSP"))
            if room_recall.get("duplicate") is not False or room_recall.get("mutationSequence") != 2:
                raise SmokeFailure("first room recall did not allocate mutation sequence 2")
            alice.receive_type("RECALL_NOTIFY", predicate=lambda m: data(m).get("messageId") == room_message_id)
            bob.receive_type("RECALL_NOTIFY", predicate=lambda m: data(m).get("messageId") == room_message_id)
            alice.send("RECALL_REQ", {"roomId": room_id, "messageId": room_message_id})
            room_retry = require_success(alice.receive_type("RECALL_RSP"))
            if room_retry.get("duplicate") is not True or room_retry.get("mutationSequence") != 2:
                raise SmokeFailure("room recall retry changed its durable outcome")

            bob.send("HISTORY_REQ", {"roomId": room_id, "count": 10, "afterSequence": 1})
            room_page = require_success(bob.receive_type("HISTORY_RSP"))
            if len(room_page.get("messages", [])) != 1 or room_page.get("nextSequence") != 2:
                raise SmokeFailure("room recall replay returned an invalid page")
            assert_replayed(room_page["messages"][0], 1, 2)

            alice.send("FRIEND_REQUEST_REQ", {"username": bob_name})
            require_success(alice.receive_type("FRIEND_REQUEST_RSP"))
            bob.send("FRIEND_PENDING_REQ")
            requests = data(bob.receive_type("FRIEND_PENDING_RSP")).get("requests", [])
            request = next(item for item in requests if item.get("fromUsername") == alice_name)
            bob.send("FRIEND_ACCEPT_REQ", {
                "requestId": request["requestId"], "fromUsername": alice_name,
            })
            require_success(bob.receive_type("FRIEND_ACCEPT_RSP"))

            friend_client_id = str(uuid.uuid4())
            send(alice, "FRIEND_CHAT_MSG", {
                "friendUsername": bob_name,
                "content": "friend recall target",
                "contentType": "text",
                "clientMessageId": friend_client_id,
            })
            friend_ack = require_success(alice.receive_type("FRIEND_CHAT_SEND_RSP"))
            friend_message_id = friend_ack.get("id")
            alice.receive_type("FRIEND_CHAT_MSG", predicate=lambda m: data(m).get("id") == friend_message_id)
            bob.receive_type("FRIEND_CHAT_MSG", predicate=lambda m: data(m).get("id") == friend_message_id)

            alice.send("FRIEND_RECALL_REQ", {"messageId": friend_message_id})
            friend_recall = require_success(alice.receive_type("FRIEND_RECALL_RSP"))
            if friend_recall.get("duplicate") is not False or friend_recall.get("mutationSequence") != 2:
                raise SmokeFailure("first friend recall did not allocate mutation sequence 2")
            bob.receive_type("FRIEND_RECALL_NOTIFY", predicate=lambda m: data(m).get("messageId") == friend_message_id)
            alice.send("FRIEND_RECALL_REQ", {"messageId": friend_message_id})
            friend_retry = require_success(alice.receive_type("FRIEND_RECALL_RSP"))
            if friend_retry.get("duplicate") is not True or friend_retry.get("mutationSequence") != 2:
                raise SmokeFailure("friend recall retry changed its durable outcome")

            bob.send("FRIEND_HISTORY_REQ", {
                "friendUsername": alice_name, "count": 10, "afterSequence": 1,
            })
            friend_page = require_success(bob.receive_type("FRIEND_HISTORY_RSP"))
            if len(friend_page.get("messages", [])) != 1 or friend_page.get("nextSequence") != 2:
                raise SmokeFailure("friend recall replay returned an invalid page")
            assert_replayed(friend_page["messages"][0], 1, 2)

            for client in clients:
                client.close()
            clients.clear()
            stop_server(process)

            with sqlite3.connect(database) as connection:
                alice_id = connection.execute(
                    "SELECT id FROM users WHERE username = ?", (alice_name,)
                ).fetchone()[0]
                connection.execute(
                    "INSERT INTO messages "
                    "(room_id, user_id, content, content_type, recalled, sequence) "
                    "VALUES (?, ?, '此消息已被撤回', 'text', 1, 3)",
                    (room_id, alice_id),
                )

            process = start_server(server, directory, database, port)
            reconnected = V1Client("127.0.0.1", port, "recall-reconnected")
            clients.append(reconnected)
            login(reconnected, bob_name, password)
            reconnected.send("HISTORY_REQ", {
                "roomId": room_id, "count": 10, "afterSequence": 1,
            })
            restarted_room = require_success(reconnected.receive_type("HISTORY_RSP"))
            assert_replayed(restarted_room["messages"][0], 1, 2)
            legacy_recall = next(
                item for item in restarted_room["messages"] if item.get("sequence") == 3
            )
            assert_replayed(legacy_recall, 3, 4)
            reconnected.send("FRIEND_HISTORY_REQ", {
                "friendUsername": alice_name, "count": 10, "afterSequence": 1,
            })
            restarted_friend = require_success(reconnected.receive_type("FRIEND_HISTORY_RSP"))
            assert_replayed(restarted_friend["messages"][0], 1, 2)
        finally:
            for client in clients:
                client.close()
            stop_server(process)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", type=Path, required=True)
    args = parser.parse_args()
    try:
        run_test(args.server.resolve())
    except (OSError, SmokeFailure, StopIteration, sqlite3.Error) as exc:
        print(f"[v1-recall-replay] FAIL: {exc}")
        return 1
    print("[v1-recall-replay] PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
