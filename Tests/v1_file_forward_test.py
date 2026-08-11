#!/usr/bin/env python3
"""Verify server-authorized attachment forwarding without client file bytes."""

from __future__ import annotations

import argparse
import base64
import tempfile
import uuid
from pathlib import Path

from v1_room_message_reliability_test import start_server, stop_server
from v1_smoke_test import (
    SmokeFailure,
    V1Client,
    data,
    find_port_range,
    login,
    register,
    require_success,
)


def make_friends(alice: V1Client, bob: V1Client, alice_name: str, bob_name: str) -> None:
    alice.send("FRIEND_REQUEST_REQ", {"username": bob_name})
    require_success(alice.receive_type("FRIEND_REQUEST_RSP"))
    bob.receive_type(
        "FRIEND_REQUEST_NOTIFY",
        predicate=lambda message: data(message).get("fromUsername") == alice_name,
    )
    bob.send("FRIEND_PENDING_REQ")
    pending = data(bob.receive_type("FRIEND_PENDING_RSP")).get("requests", [])
    request = next(
        (
            item
            for item in pending
            if isinstance(item, dict) and item.get("fromUsername") == alice_name
        ),
        None,
    )
    if not isinstance(request, dict) or not isinstance(request.get("requestId"), int):
        raise SmokeFailure("friend request was absent from the pending list")
    bob.send(
        "FRIEND_ACCEPT_REQ",
        {"requestId": request["requestId"], "fromUsername": alice_name},
    )
    require_success(bob.receive_type("FRIEND_ACCEPT_RSP"))
    alice.receive_type(
        "FRIEND_ACCEPT_NOTIFY",
        predicate=lambda message: data(message).get("acceptedBy") == bob_name,
    )


def download(client: V1Client, file_id: int) -> bytes:
    client.send("FILE_DOWNLOAD_REQ", {"fileId": file_id})
    payload = require_success(
        client.receive_type(
            "FILE_DOWNLOAD_RSP",
            predicate=lambda message: data(message).get("fileId") == file_id,
        )
    )
    return base64.b64decode(str(payload.get("fileData", "")))


def run_test(server: Path) -> None:
    port = find_port_range()
    password = "file-forward-password"
    suffix = uuid.uuid4().hex[:7]
    payload = b"server-side-forward\x00bytes"
    with tempfile.TemporaryDirectory(prefix="chat-file-forward-") as temp_name:
        directory = Path(temp_name)
        process = start_server(server, directory, directory / "file-forward.db", port)
        clients: list[V1Client] = []
        try:
            alice = V1Client("127.0.0.1", port, "forward-alice")
            bob = V1Client("127.0.0.1", port, "forward-bob")
            outsider = V1Client("127.0.0.1", port, "forward-outsider")
            clients.extend([alice, bob, outsider])
            alice_name = f"fa_{suffix}"
            bob_name = f"fb_{suffix}"
            outsider_name = f"fo_{suffix}"
            alice_login: dict[str, object] | None = None
            for client, username, display_name in (
                (alice, alice_name, "Forward Alice"),
                (bob, bob_name, "Forward Bob"),
                (outsider, outsider_name, "Forward Outsider"),
            ):
                register(client, username, display_name, password)
                login_payload = login(client, username, password)
                if client is alice:
                    alice_login = login_payload
            if not alice_login or alice_login.get("serverFileForward") is not True:
                raise SmokeFailure("login did not advertise server-side file forwarding")

            alice.send("CREATE_ROOM_REQ", {"roomName": "Forward Source"})
            source_room = require_success(alice.receive_type("CREATE_ROOM_RSP"))["roomId"]
            bob.send("JOIN_ROOM_REQ", {"roomId": source_room})
            require_success(bob.receive_type("JOIN_ROOM_RSP"))
            alice.send("CREATE_ROOM_REQ", {"roomName": "Forward Target"})
            target_room = require_success(alice.receive_type("CREATE_ROOM_RSP"))["roomId"]
            outsider.send("CREATE_ROOM_REQ", {"roomName": "Denied Target"})
            denied_room = require_success(outsider.receive_type("CREATE_ROOM_RSP"))["roomId"]
            make_friends(alice, bob, alice_name, bob_name)

            alice.send(
                "FILE_SEND",
                {
                    "roomId": source_room,
                    "fileName": "forward.bin",
                    "fileSize": len(payload),
                    "fileData": base64.b64encode(payload).decode("ascii"),
                },
            )
            match_source = lambda message: (
                data(message).get("roomId") == source_room
                and data(message).get("fileName") == "forward.bin"
            )
            source_notice = data(alice.receive_type("FILE_NOTIFY", predicate=match_source))
            bob.receive_type("FILE_NOTIFY", predicate=match_source)
            source_file_id = source_notice.get("fileId")
            if not isinstance(source_file_id, int) or source_file_id <= 0:
                raise SmokeFailure("source attachment did not receive a stable file id")

            alice.send(
                "FILE_FORWARD_REQ",
                {
                    "sourceFileId": source_file_id,
                    "roomIds": [target_room],
                    "friendUsernames": [bob_name],
                },
            )
            target_notice = data(
                alice.receive_type(
                    "FILE_NOTIFY",
                    predicate=lambda message: data(message).get("roomId") == target_room,
                )
            )
            friend_notice = data(
                alice.receive_type(
                    "FRIEND_FILE_NOTIFY",
                    predicate=lambda message: data(message).get("friendUsername") == bob_name,
                )
            )
            bob_friend_notice = data(
                bob.receive_type(
                    "FRIEND_FILE_NOTIFY",
                    predicate=lambda message: data(message).get("sender") == alice_name,
                )
            )
            response = require_success(alice.receive_type("FILE_FORWARD_RSP"))
            if response.get("forwardedCount") != 2 or response.get("failedCount") != 0:
                raise SmokeFailure(f"unexpected forwarding summary: {response}")
            if any("fileData" in notice for notice in (target_notice, friend_notice, bob_friend_notice)):
                raise SmokeFailure("forward notification leaked inline file bytes")
            for notice in (target_notice, friend_notice, bob_friend_notice):
                if not isinstance(notice.get("sequence"), int) or notice["sequence"] <= 0:
                    raise SmokeFailure("forward notification omitted its durable sequence")
                if not isinstance(notice.get("timestamp"), int) or notice["timestamp"] <= 0:
                    raise SmokeFailure("forward notification omitted its authoritative timestamp")
            room_file_id = target_notice.get("fileId")
            friend_file_id = friend_notice.get("fileId")
            if not isinstance(room_file_id, int) or room_file_id <= 0:
                raise SmokeFailure("room forward did not create a new file id")
            if not isinstance(friend_file_id, int) or friend_file_id >= 0:
                raise SmokeFailure("friend forward did not create a signed friend file id")
            if room_file_id == source_file_id or friend_file_id == source_file_id:
                raise SmokeFailure("forward targets reused the source file identity")
            if download(alice, room_file_id) != payload or download(bob, friend_file_id) != payload:
                raise SmokeFailure("forwarded attachment bytes changed")

            alice.send(
                "FILE_FORWARD_REQ",
                {
                    "sourceFileId": source_file_id,
                    "roomIds": [target_room, denied_room],
                },
            )
            denied_target = data(alice.receive_type("FILE_FORWARD_RSP"))
            if denied_target.get("forwardedCount") != 1 or denied_target.get("failedCount") != 1:
                raise SmokeFailure("partial target authorization was not reported accurately")

            alice.send(
                "FILE_FORWARD_REQ",
                {"sourceFileId": 1.5, "roomIds": [target_room]},
            )
            invalid_source = data(alice.receive_type("FILE_FORWARD_RSP"))
            if invalid_source.get("errorCode") != "INVALID_SOURCE_FILE_ID":
                raise SmokeFailure("fractional source file id was accepted")

            alice.send(
                "FILE_FORWARD_REQ",
                {
                    "sourceFileId": source_file_id,
                    "roomIds": list(range(100_000, 100_011)),
                },
            )
            too_many = data(alice.receive_type("FILE_FORWARD_RSP"))
            if too_many.get("errorCode") != "INVALID_FORWARD_TARGETS":
                raise SmokeFailure("forward target-count bound was not enforced")

            outsider.send(
                "FILE_FORWARD_REQ",
                {"sourceFileId": source_file_id, "roomIds": [denied_room]},
            )
            denied_source = data(outsider.receive_type("FILE_FORWARD_RSP"))
            if denied_source.get("errorCode") != "SOURCE_FILE_ACCESS_DENIED":
                raise SmokeFailure("foreign source file was accepted for forwarding")
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
    except (OSError, SmokeFailure, KeyError, ValueError) as exc:
        print(f"[v1-file-forward] FAIL: {exc}")
        return 1
    print("[v1-file-forward] PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
