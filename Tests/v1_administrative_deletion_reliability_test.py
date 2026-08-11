#!/usr/bin/env python3
"""Verify durable, idempotent and replayable V1 administrative deletion."""

from __future__ import annotations

import argparse
import tempfile
import time
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


def send_text(client: V1Client, room_id: int, content: str) -> dict[str, object]:
    client_message_id = str(uuid.uuid4())
    send(client, "CHAT_MSG", {
        "roomId": room_id,
        "content": content,
        "contentType": "text",
        "clientMessageId": client_message_id,
    })
    accepted = require_success(client.receive_type(
        "CHAT_SEND_RSP",
        predicate=lambda message: data(message).get("clientMessageId") == client_message_id,
    ))
    client.receive_type(
        "CHAT_MSG", predicate=lambda message: data(message).get("id") == accepted.get("id")
    )
    return accepted


def delete(
    client: V1Client,
    room_id: int,
    mode: str,
    operation_id: str,
    *,
    message_ids: list[int] | None = None,
    timestamp: int = 0,
) -> dict[str, object]:
    payload: dict[str, object] = {
        "roomId": room_id,
        "mode": mode,
        "clientOperationId": operation_id,
    }
    if message_ids is not None:
        payload["messageIds"] = message_ids
    if timestamp:
        payload["timestamp"] = timestamp
    send(client, "DELETE_MSGS_REQ", payload)
    return data(client.receive_type(
        "DELETE_MSGS_RSP",
        predicate=lambda message: data(message).get("clientOperationId") == operation_id,
    ))


def require_delete_outcome(
    payload: dict[str, object], *, duplicate: bool, sequence: int, count: int
) -> None:
    if (
        payload.get("success") is not True
        or payload.get("duplicate") is not duplicate
        or payload.get("sequence") != sequence
        or payload.get("syncSequence") != sequence
        or payload.get("deletedCount") != count
    ):
        raise SmokeFailure(f"invalid administrative deletion outcome: {payload}")


def run_test(server: Path) -> None:
    port = find_port_range()
    suffix = uuid.uuid4().hex[:7]
    admin_name, member_name = f"da_{suffix}", f"dm_{suffix}"
    password = "administrative-deletion-password"
    selected_operation = str(uuid.uuid4())

    with tempfile.TemporaryDirectory(prefix="chat-admin-delete-") as temp_name:
        directory = Path(temp_name)
        database = directory / "admin-delete.db"
        process = start_server(server, directory, database, port)
        clients: list[V1Client] = []
        try:
            admin = V1Client("127.0.0.1", port, "deletion-admin")
            member = V1Client("127.0.0.1", port, "deletion-member")
            clients.extend([admin, member])
            register(admin, admin_name, "Deletion Admin", password)
            register(member, member_name, "Deletion Member", password)
            login(admin, admin_name, password)
            login(member, member_name, password)

            admin.send("CREATE_ROOM_REQ", {"roomName": "Deletion Reliability Room"})
            room_id = require_success(admin.receive_type("CREATE_ROOM_RSP")).get("roomId")
            if not isinstance(room_id, int):
                raise SmokeFailure("room creation omitted room id")
            member.send("JOIN_ROOM_REQ", {"roomId": room_id, "password": ""})
            require_success(member.receive_type("JOIN_ROOM_RSP"))

            first = send_text(admin, room_id, "first")
            member.receive_type("CHAT_MSG", predicate=lambda m: data(m).get("id") == first["id"])
            second = send_text(admin, room_id, "second")
            member.receive_type("CHAT_MSG", predicate=lambda m: data(m).get("id") == second["id"])
            third = send_text(admin, room_id, "third")
            member.receive_type("CHAT_MSG", predicate=lambda m: data(m).get("id") == third["id"])

            denied = delete(
                member, room_id, "selected", str(uuid.uuid4()),
                message_ids=[int(first["id"])],
            )
            if denied.get("success") is not False or denied.get("errorCode") != "ADMIN_DELETE_ACCESS_DENIED":
                raise SmokeFailure("non-admin deletion lacked a stable denial")

            selected = delete(
                admin, room_id, "selected", selected_operation,
                message_ids=[int(second["id"])],
            )
            require_delete_outcome(selected, duplicate=False, sequence=4, count=1)
            notice = data(member.receive_type(
                "DELETE_MSGS_NOTIFY",
                predicate=lambda m: data(m).get("clientOperationId") == selected_operation,
            ))
            if notice.get("messageIds") != [second["id"]] or notice.get("eventType") != "messagesDeleted":
                raise SmokeFailure("live deletion notification omitted durable event fields")

            retry = delete(
                admin, room_id, "selected", selected_operation,
                message_ids=[int(second["id"])],
            )
            require_delete_outcome(retry, duplicate=True, sequence=4, count=1)
            conflict = delete(
                admin, room_id, "selected", selected_operation,
                message_ids=[int(first["id"])],
            )
            if conflict.get("success") is not False or conflict.get("errorCode") != "CLIENT_OPERATION_ID_CONFLICT":
                raise SmokeFailure("operation id reuse with different targets was accepted")

            fourth = send_text(admin, room_id, "fourth")
            member.receive_type("CHAT_MSG", predicate=lambda m: data(m).get("id") == fourth["id"])
            before = delete(
                admin, room_id, "before", str(uuid.uuid4()),
                timestamp=int(time.time() * 1000) + 60_000,
            )
            require_delete_outcome(before, duplicate=False, sequence=6, count=3)
            member.receive_type("DELETE_MSGS_NOTIFY", predicate=lambda m: data(m).get("sequence") == 6)

            fifth = send_text(admin, room_id, "fifth")
            member.receive_type("CHAT_MSG", predicate=lambda m: data(m).get("id") == fifth["id"])
            after = delete(
                admin, room_id, "after", str(uuid.uuid4()), timestamp=1,
            )
            require_delete_outcome(after, duplicate=False, sequence=8, count=1)
            member.receive_type("DELETE_MSGS_NOTIFY", predicate=lambda m: data(m).get("sequence") == 8)

            sixth = send_text(admin, room_id, "sixth")
            member.receive_type("CHAT_MSG", predicate=lambda m: data(m).get("id") == sixth["id"])
            cleared = delete(admin, room_id, "all", str(uuid.uuid4()))
            require_delete_outcome(cleared, duplicate=False, sequence=10, count=1)
            member.receive_type("DELETE_MSGS_NOTIFY", predicate=lambda m: data(m).get("sequence") == 10)

            oversized = delete(
                admin, room_id, "selected", str(uuid.uuid4()),
                message_ids=list(range(1, 102)),
            )
            if oversized.get("success") is not False or oversized.get("errorCode") != "INVALID_MESSAGE_SELECTION":
                raise SmokeFailure("oversized deletion selection was accepted")

            member.send("HISTORY_REQ", {"roomId": room_id, "count": 2, "afterSequence": 0})
            page_one = require_success(member.receive_type("HISTORY_RSP"))
            if [event.get("sequence") for event in page_one.get("events", [])] != [4, 6]:
                raise SmokeFailure(f"first deletion replay page was invalid: {page_one}")
            if page_one.get("nextSequence") != 6 or page_one.get("hasMore") is not True:
                raise SmokeFailure("first deletion replay cursor was invalid")
            member.send("HISTORY_REQ", {"roomId": room_id, "count": 2, "afterSequence": 6})
            page_two = require_success(member.receive_type("HISTORY_RSP"))
            if [event.get("sequence") for event in page_two.get("events", [])] != [8, 10]:
                raise SmokeFailure(f"second deletion replay page was invalid: {page_two}")
            if page_two.get("nextSequence") != 10 or page_two.get("hasMore") is not False:
                raise SmokeFailure("final deletion replay cursor was invalid")

            for client in clients:
                client.close()
            clients.clear()
            stop_server(process)

            process = start_server(server, directory, database, port)
            restarted = V1Client("127.0.0.1", port, "deletion-restarted")
            clients.append(restarted)
            login(restarted, admin_name, password)
            persisted = delete(
                restarted, room_id, "selected", selected_operation,
                message_ids=[int(second["id"])],
            )
            require_delete_outcome(persisted, duplicate=True, sequence=4, count=1)
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
    except (OSError, SmokeFailure, KeyError) as exc:
        print(f"[v1-administrative-deletion] FAIL: {exc}")
        return 1
    print("[v1-administrative-deletion] PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
