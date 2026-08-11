#!/usr/bin/env python3
"""Verify V1-compatible room-message idempotency, sequencing, and resume."""

from __future__ import annotations

import argparse
import json
import os
import socket
import sqlite3
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


def send(
    client: V1Client,
    message_type: str,
    payload: dict[str, object],
    *,
    envelope_id: str | None = None,
) -> str:
    message_id = envelope_id or str(uuid.uuid4())
    message = {
        "type": message_type,
        "id": message_id,
        "timestamp": int(time.time() * 1000),
        "data": payload,
    }
    encoded = json.dumps(message, separators=(",", ":"), ensure_ascii=False).encode()
    client.socket.sendall(struct.pack(">I", len(encoded)) + encoded)
    return message_id


def start_server(
    server: Path, directory: Path, database: Path, port: int
) -> subprocess.Popen[str]:
    environment = os.environ.copy()
    environment["CHATROOM_DB_PATH"] = str(database)
    environment["CHATROOM_DEVELOPER_KEY"] = "v1-reliability-developer-key"
    process = subprocess.Popen(
        [str(server), "--port", str(port)],
        cwd=directory,
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    wait_for_server(process, port)
    return process


def stop_server(process: subprocess.Popen[str]) -> str:
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)
    return process.stdout.read() if process.stdout else ""


def expect_acceptance(
    client: V1Client, client_message_id: str, *, duplicate: bool
) -> dict[str, object]:
    response = client.receive_type(
        "CHAT_SEND_RSP",
        predicate=lambda message: data(message).get("clientMessageId")
        == client_message_id,
    )
    payload = require_success(response)
    if payload.get("duplicate") is not duplicate:
        raise SmokeFailure(
            f"wrong duplicate flag for {client_message_id}: {payload.get('duplicate')}"
        )
    if not isinstance(payload.get("id"), int) or not isinstance(
        payload.get("sequence"), int
    ):
        raise SmokeFailure("accepted room message lacks stable id/sequence")
    return payload


def run_test(server: Path) -> None:
    port = find_port_range()
    username = f"reliable_{uuid.uuid4().hex[:7]}"
    outsider_name = f"outsider_{uuid.uuid4().hex[:7]}"
    password = "reliability-test-password"
    first_id = str(uuid.uuid4())
    second_id = str(uuid.uuid4())
    legacy_envelope_id = str(uuid.uuid4())
    outputs: list[str] = []

    with tempfile.TemporaryDirectory(prefix="chat-room-reliable-") as temp_name:
        directory = Path(temp_name)
        database = directory / "reliable.db"
        process = start_server(server, directory, database, port)
        clients: list[V1Client] = []
        try:
            sender = V1Client("127.0.0.1", port, "reliable-sender")
            outsider = V1Client("127.0.0.1", port, "reliable-outsider")
            clients.extend([sender, outsider])
            register(sender, username, "Reliable Sender", password)
            register(outsider, outsider_name, "Outsider", password)
            login(sender, username, password)
            login(outsider, outsider_name, password)

            sender.send("CREATE_ROOM_REQ", {"roomName": "Reliable Room"})
            room_id = require_success(
                sender.receive_type("CREATE_ROOM_RSP")
            ).get("roomId")
            if not isinstance(room_id, int):
                raise SmokeFailure("room creation did not return an integer room id")

            first_payload = {
                "roomId": room_id,
                "content": "first durable message",
                "contentType": "text",
                "clientMessageId": first_id,
            }
            send(sender, "CHAT_MSG", first_payload)
            first = expect_acceptance(sender, first_id, duplicate=False)
            delivered = data(
                sender.receive_type(
                    "CHAT_MSG",
                    predicate=lambda message: data(message).get("clientMessageId")
                    == first_id,
                )
            )
            if delivered.get("id") != first["id"] or delivered.get("sequence") != 1:
                raise SmokeFailure("committed message and acceptance disagree")

            send(sender, "CHAT_MSG", first_payload)
            duplicate = expect_acceptance(sender, first_id, duplicate=True)
            if duplicate["id"] != first["id"] or duplicate["sequence"] != 1:
                raise SmokeFailure("duplicate retry did not return original outcome")

            conflicting = dict(first_payload)
            conflicting["content"] = "different payload"
            send(sender, "CHAT_MSG", conflicting)
            conflict = data(
                sender.receive_type(
                    "CHAT_SEND_RSP",
                    predicate=lambda message: data(message).get("clientMessageId")
                    == first_id,
                )
            )
            if conflict.get("success") is not False or conflict.get("errorCode") != "CLIENT_MESSAGE_ID_CONFLICT":
                raise SmokeFailure("client message id reuse with a new payload was not rejected")

            second_payload = {
                "roomId": room_id,
                "content": "second durable message",
                "contentType": "text",
                "clientMessageId": second_id,
            }
            send(sender, "CHAT_MSG", second_payload)
            second = expect_acceptance(sender, second_id, duplicate=False)
            sender.receive_type(
                "CHAT_MSG",
                predicate=lambda message: data(message).get("clientMessageId")
                == second_id,
            )
            if second.get("sequence") != 2:
                raise SmokeFailure("room sequence did not advance contiguously")

            # An old client has no data.clientMessageId. Its stable envelope id is
            # used as the compatibility idempotency key when the exact frame retries.
            legacy_payload = {
                "roomId": room_id,
                "content": "legacy compatible message",
                "contentType": "text",
            }
            send(sender, "CHAT_MSG", legacy_payload, envelope_id=legacy_envelope_id)
            legacy = expect_acceptance(sender, legacy_envelope_id, duplicate=False)
            sender.receive_type(
                "CHAT_MSG",
                predicate=lambda message: data(message).get("clientMessageId")
                == legacy_envelope_id,
            )
            send(sender, "CHAT_MSG", legacy_payload, envelope_id=legacy_envelope_id)
            legacy_duplicate = expect_acceptance(
                sender, legacy_envelope_id, duplicate=True
            )
            if legacy_duplicate["id"] != legacy["id"]:
                raise SmokeFailure("legacy envelope retry created a new message")

            send(
                sender,
                "CHAT_MSG",
                {
                    "roomId": room_id,
                    "content": "invalid identifier",
                    "contentType": "text",
                    "clientMessageId": "x" * 129,
                },
            )
            invalid_id = data(sender.receive_type("CHAT_SEND_RSP"))
            if (
                invalid_id.get("success") is not False
                or invalid_id.get("errorCode") != "INVALID_CLIENT_MESSAGE_ID"
                or "clientMessageId" in invalid_id
            ):
                raise SmokeFailure("oversized client id was accepted or reflected")

            send(
                outsider,
                "CHAT_MSG",
                {
                    "roomId": room_id,
                    "content": "unauthorized",
                    "contentType": "text",
                    "clientMessageId": str(uuid.uuid4()),
                },
            )
            denied = data(outsider.receive_type("CHAT_SEND_RSP"))
            if denied.get("success") is not False or denied.get("errorCode") != "ROOM_ACCESS_DENIED":
                raise SmokeFailure("non-member room send was not explicitly rejected")

            sender.send("HISTORY_REQ", {"roomId": room_id, "count": 2, "afterSequence": 0})
            first_page = require_success(sender.receive_type("HISTORY_RSP"))
            if [item.get("sequence") for item in first_page.get("messages", [])] != [1, 2]:
                raise SmokeFailure("first sequence page was not ordered by sequence")
            if first_page.get("nextSequence") != 2 or first_page.get("hasMore") is not True:
                raise SmokeFailure("first sequence page returned an invalid resume cursor")

            sender.send("HISTORY_REQ", {"roomId": room_id, "count": 2, "afterSequence": 2})
            second_page = require_success(sender.receive_type("HISTORY_RSP"))
            if [item.get("sequence") for item in second_page.get("messages", [])] != [3]:
                raise SmokeFailure("second sequence page did not recover the missing range")
            if second_page.get("nextSequence") != 3 or second_page.get("hasMore") is not False:
                raise SmokeFailure("final sequence page did not advance to the high watermark")

            for client in clients:
                client.close()
            clients.clear()
            outputs.append(stop_server(process))

            process = start_server(server, directory, database, port)
            reconnected = V1Client("127.0.0.1", port, "reliable-reconnected")
            clients.append(reconnected)
            login(reconnected, username, password)
            send(reconnected, "CHAT_MSG", first_payload)
            after_restart = expect_acceptance(reconnected, first_id, duplicate=True)
            if after_restart["id"] != first["id"] or after_restart["sequence"] != 1:
                raise SmokeFailure("idempotency outcome did not survive process restart")

            reconnected.close()
            clients.clear()
            outputs.append(stop_server(process))

            # Simulate an interrupted expand/migrate deployment: a row exists
            # without sequence while the new nullable column is already present.
            with sqlite3.connect(database) as connection:
                user_id = connection.execute(
                    "SELECT id FROM users WHERE username = ?", (username,)
                ).fetchone()[0]
                connection.execute(
                    "INSERT INTO messages (room_id, user_id, content, content_type, sequence) "
                    "VALUES (?, ?, 'migration recovery', 'text', NULL)",
                    (room_id, user_id),
                )

            process = start_server(server, directory, database, port)
            migrated = V1Client("127.0.0.1", port, "reliable-migrated")
            clients.append(migrated)
            login(migrated, username, password)
            migrated.send(
                "HISTORY_REQ", {"roomId": room_id, "count": 10, "afterSequence": 3}
            )
            recovery = require_success(migrated.receive_type("HISTORY_RSP"))
            recovered = recovery.get("messages", [])
            if len(recovered) != 1 or recovered[0].get("sequence") != 4:
                raise SmokeFailure("restart-safe sequence backfill did not recover a partial migration")
            if recovery.get("nextSequence") != 4 or recovery.get("lastSequence") != 4:
                raise SmokeFailure("partial migration did not raise the durable high watermark")

            migrated.close()
            clients.clear()
            outputs.append(stop_server(process))

            # A deleted highest row must not let a later old-server/null row
            # reuse that sequence after another migration restart.
            with sqlite3.connect(database) as connection:
                connection.execute(
                    "DELETE FROM messages WHERE room_id = ? AND sequence = 4", (room_id,)
                )
                connection.execute(
                    "INSERT INTO messages (room_id, user_id, content, content_type, sequence) "
                    "VALUES (?, ?, 'post-delete migration', 'text', NULL)",
                    (room_id, user_id),
                )

            process = start_server(server, directory, database, port)
            monotonic = V1Client("127.0.0.1", port, "reliable-monotonic")
            clients.append(monotonic)
            login(monotonic, username, password)
            monotonic.send(
                "HISTORY_REQ", {"roomId": room_id, "count": 10, "afterSequence": 3}
            )
            monotonic_page = require_success(monotonic.receive_type("HISTORY_RSP"))
            monotonic_rows = monotonic_page.get("messages", [])
            if len(monotonic_rows) != 1 or monotonic_rows[0].get("sequence") != 5:
                raise SmokeFailure("migration reused a deleted durable sequence")
            if monotonic_page.get("lastSequence") != 5:
                raise SmokeFailure("durable high watermark did not remain monotonic")
        finally:
            for client in clients:
                client.close()
            outputs.append(stop_server(process))

    combined_output = "\n".join(outputs)
    for marker in ("outcome=accepted", "outcome=duplicate", "outcome=rejected"):
        if marker not in combined_output:
            raise SmokeFailure(f"structured messaging monitor lacks {marker}")

    print(
        "[V1RoomMessageReliabilityTest] PASS: idempotent retry/conflict, old-client "
        "compatibility, authorization, ordered sequence resume, restart durability, "
        "partial-migration recovery, and structured outcomes"
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", type=Path, required=True)
    args = parser.parse_args()
    try:
        run_test(args.server.resolve())
        return 0
    except (OSError, SmokeFailure, subprocess.SubprocessError, sqlite3.Error) as error:
        print(f"[V1RoomMessageReliabilityTest] FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
