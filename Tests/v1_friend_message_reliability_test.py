#!/usr/bin/env python3
"""Verify V1 friend-message idempotency, ordering, authorization, and resume."""

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


def expect_acceptance(
    client: V1Client, client_message_id: str, *, duplicate: bool
) -> dict[str, object]:
    payload = require_success(
        client.receive_type(
            "FRIEND_CHAT_SEND_RSP",
            predicate=lambda message: data(message).get("clientMessageId")
            == client_message_id,
        )
    )
    if payload.get("duplicate") is not duplicate:
        raise SmokeFailure("friend send returned the wrong duplicate flag")
    if not isinstance(payload.get("id"), int) or not isinstance(
        payload.get("sequence"), int
    ):
        raise SmokeFailure("accepted friend message lacks a stable id/sequence")
    return payload


def run_test(server: Path) -> None:
    port = find_port_range()
    suffix = uuid.uuid4().hex[:7]
    alice_name = f"fa_{suffix}"
    bob_name = f"fb_{suffix}"
    outsider_name = f"fo_{suffix}"
    password = "friend-reliability-password"
    first_id, second_id, legacy_id = (str(uuid.uuid4()) for _ in range(3))
    outputs: list[str] = []

    with tempfile.TemporaryDirectory(prefix="chat-friend-reliable-") as temp_name:
        directory = Path(temp_name)
        database = directory / "friend-reliable.db"
        process = start_server(server, directory, database, port)
        clients: list[V1Client] = []
        try:
            alice = V1Client("127.0.0.1", port, "friend-alice")
            bob = V1Client("127.0.0.1", port, "friend-bob")
            outsider = V1Client("127.0.0.1", port, "friend-outsider")
            clients.extend([alice, bob, outsider])
            for client, username, display_name in (
                (alice, alice_name, "Friend Alice"),
                (bob, bob_name, "Friend Bob"),
                (outsider, outsider_name, "Friend Outsider"),
            ):
                register(client, username, display_name, password)
                login(client, username, password)

            alice.send("FRIEND_REQUEST_REQ", {"username": bob_name})
            require_success(alice.receive_type("FRIEND_REQUEST_RSP"))
            bob.send("FRIEND_PENDING_REQ")
            requests = data(bob.receive_type("FRIEND_PENDING_RSP")).get("requests", [])
            request = next(
                (item for item in requests if item.get("fromUsername") == alice_name),
                None,
            )
            if not request:
                raise SmokeFailure("friend request was not persisted")
            bob.send(
                "FRIEND_ACCEPT_REQ",
                {"requestId": request["requestId"], "fromUsername": alice_name},
            )
            require_success(bob.receive_type("FRIEND_ACCEPT_RSP"))

            first_payload = {
                "friendUsername": bob_name,
                "content": "first durable friend message",
                "contentType": "text",
                "clientMessageId": first_id,
            }
            send(alice, "FRIEND_CHAT_MSG", first_payload)
            first = expect_acceptance(alice, first_id, duplicate=False)
            first_alice = data(
                alice.receive_type(
                    "FRIEND_CHAT_MSG",
                    predicate=lambda message: data(message).get("clientMessageId")
                    == first_id,
                )
            )
            first_bob = data(
                bob.receive_type(
                    "FRIEND_CHAT_MSG",
                    predicate=lambda message: data(message).get("clientMessageId")
                    == first_id,
                )
            )
            if first_alice.get("id") != first["id"] or first_bob.get("sequence") != 1:
                raise SmokeFailure("friend participants observed different committed identity")

            send(alice, "FRIEND_CHAT_MSG", first_payload)
            duplicate = expect_acceptance(alice, first_id, duplicate=True)
            if duplicate["id"] != first["id"] or duplicate["sequence"] != 1:
                raise SmokeFailure("friend retry did not return the original outcome")

            conflict_payload = dict(first_payload)
            conflict_payload["content"] = "conflicting friend message"
            send(alice, "FRIEND_CHAT_MSG", conflict_payload)
            conflict = data(alice.receive_type("FRIEND_CHAT_SEND_RSP"))
            if conflict.get("errorCode") != "CLIENT_MESSAGE_ID_CONFLICT":
                raise SmokeFailure("friend client id conflict was not rejected")

            second_payload = {
                "friendUsername": bob_name,
                "content": "second durable friend message",
                "contentType": "text",
                "clientMessageId": second_id,
            }
            send(alice, "FRIEND_CHAT_MSG", second_payload)
            second = expect_acceptance(alice, second_id, duplicate=False)
            alice.receive_type("FRIEND_CHAT_MSG", predicate=lambda m: data(m).get("clientMessageId") == second_id)
            bob.receive_type("FRIEND_CHAT_MSG", predicate=lambda m: data(m).get("clientMessageId") == second_id)
            if second["sequence"] != 2:
                raise SmokeFailure("friend sequence did not advance")

            legacy_payload = {
                "friendUsername": bob_name,
                "content": "legacy compatible friend message",
                "contentType": "text",
            }
            send(alice, "FRIEND_CHAT_MSG", legacy_payload, envelope_id=legacy_id)
            legacy = expect_acceptance(alice, legacy_id, duplicate=False)
            alice.receive_type("FRIEND_CHAT_MSG", predicate=lambda m: data(m).get("clientMessageId") == legacy_id)
            bob.receive_type("FRIEND_CHAT_MSG", predicate=lambda m: data(m).get("clientMessageId") == legacy_id)
            send(alice, "FRIEND_CHAT_MSG", legacy_payload, envelope_id=legacy_id)
            if expect_acceptance(alice, legacy_id, duplicate=True)["id"] != legacy["id"]:
                raise SmokeFailure("legacy friend retry created a new row")

            send(alice, "FRIEND_CHAT_MSG", {
                "friendUsername": bob_name,
                "content": "invalid id",
                "contentType": "text",
                "clientMessageId": "x" * 129,
            })
            invalid = data(alice.receive_type("FRIEND_CHAT_SEND_RSP"))
            if invalid.get("errorCode") != "INVALID_CLIENT_MESSAGE_ID" or "clientMessageId" in invalid:
                raise SmokeFailure("oversized friend client id was accepted or reflected")

            outsider_id = str(uuid.uuid4())
            send(outsider, "FRIEND_CHAT_MSG", {
                "friendUsername": bob_name,
                "content": "unauthorized",
                "contentType": "text",
                "clientMessageId": outsider_id,
            })
            denied = data(outsider.receive_type("FRIEND_CHAT_SEND_RSP"))
            if denied.get("errorCode") != "FRIENDSHIP_ACCESS_DENIED":
                raise SmokeFailure("non-friend send was not explicitly rejected")

            bob.send("FRIEND_HISTORY_REQ", {"friendUsername": alice_name, "count": 2, "afterSequence": -1})
            bad_cursor = data(bob.receive_type("FRIEND_HISTORY_RSP"))
            if bad_cursor.get("errorCode") != "INVALID_SEQUENCE_CURSOR":
                raise SmokeFailure("negative friend sequence cursor was not rejected")

            bob.send("FRIEND_HISTORY_REQ", {"friendUsername": alice_name, "count": 2, "afterSequence": 0})
            page_one = require_success(bob.receive_type("FRIEND_HISTORY_RSP"))
            if [item.get("sequence") for item in page_one["messages"]] != [1, 2] or page_one.get("nextSequence") != 2:
                raise SmokeFailure("first friend sequence page was invalid")
            bob.send("FRIEND_HISTORY_REQ", {"friendUsername": alice_name, "count": 2, "afterSequence": 2})
            page_two = require_success(bob.receive_type("FRIEND_HISTORY_RSP"))
            if [item.get("sequence") for item in page_two["messages"]] != [3] or page_two.get("hasMore") is not False:
                raise SmokeFailure("final friend sequence page was invalid")

            friendship_id = int(first["friendshipId"])
            for client in clients:
                client.close()
            clients.clear()
            outputs.append(stop_server(process))

            process = start_server(server, directory, database, port)
            reconnected = V1Client("127.0.0.1", port, "friend-reconnected")
            clients.append(reconnected)
            login(reconnected, alice_name, password)
            send(reconnected, "FRIEND_CHAT_MSG", first_payload)
            restarted = expect_acceptance(reconnected, first_id, duplicate=True)
            if restarted["id"] != first["id"] or restarted["sequence"] != 1:
                raise SmokeFailure("friend idempotency did not survive restart")
            reconnected.close()
            clients.clear()
            outputs.append(stop_server(process))

            with sqlite3.connect(database) as connection:
                sender_id = connection.execute(
                    "SELECT id FROM users WHERE username = ?", (alice_name,)
                ).fetchone()[0]
                connection.execute(
                    "INSERT INTO friend_messages(friendship_id, sender_id, content, content_type) VALUES (?, ?, 'legacy row', 'text')",
                    (friendship_id, sender_id),
                )

            process = start_server(server, directory, database, port)
            migrated = V1Client("127.0.0.1", port, "friend-migrated")
            clients.append(migrated)
            login(migrated, alice_name, password)
            migrated.send("FRIEND_HISTORY_REQ", {"friendUsername": bob_name, "count": 10, "afterSequence": 3})
            migration_page = require_success(migrated.receive_type("FRIEND_HISTORY_RSP"))
            if [item.get("sequence") for item in migration_page["messages"]] != [4]:
                raise SmokeFailure("legacy friend row was not assigned the next sequence")
            migrated.close()
            clients.clear()
            outputs.append(stop_server(process))

            with sqlite3.connect(database) as connection:
                connection.execute(
                    "DELETE FROM friend_messages WHERE friendship_id = ? AND sequence = 4",
                    (friendship_id,),
                )
                connection.execute(
                    "INSERT INTO friend_messages(friendship_id, sender_id, content, content_type) VALUES (?, ?, 'post-delete row', 'text')",
                    (friendship_id, sender_id),
                )
            process = start_server(server, directory, database, port)
            final = V1Client("127.0.0.1", port, "friend-final")
            clients.append(final)
            login(final, alice_name, password)
            final.send("FRIEND_HISTORY_REQ", {"friendUsername": bob_name, "count": 10, "afterSequence": 3})
            final_page = require_success(final.receive_type("FRIEND_HISTORY_RSP"))
            if [item.get("sequence") for item in final_page["messages"]] != [5] or final_page.get("lastSequence") != 5:
                raise SmokeFailure("friend sequence high watermark was reused after deletion")
            with sqlite3.connect(database) as connection:
                connection.execute(
                    "DELETE FROM friend_messages WHERE friendship_id = ? AND sequence = 5",
                    (friendship_id,),
                )
            final.send("FRIEND_HISTORY_REQ", {
                "friendUsername": bob_name, "count": 10, "afterSequence": 4,
            })
            gap_page = require_success(final.receive_type("FRIEND_HISTORY_RSP"))
            if gap_page["messages"] or gap_page.get("nextSequence") != 5 or gap_page.get("hasMore") is not False:
                raise SmokeFailure("empty friend deletion gap did not advance to the high watermark")
        finally:
            for client in clients:
                client.close()
            outputs.append(stop_server(process))

    logs = "\n".join(outputs)
    for outcome in ("accepted", "duplicate", "rejected"):
        if f"[Messaging] friend-send outcome={outcome}" not in logs:
            raise SmokeFailure(f"missing friend messaging outcome log: {outcome}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", type=Path, required=True)
    args = parser.parse_args()
    try:
        run_test(args.server.resolve())
    except (OSError, SmokeFailure, sqlite3.Error) as exc:
        print(f"[v1-friend-reliability] FAIL: {exc}")
        return 1
    print("[v1-friend-reliability] PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
