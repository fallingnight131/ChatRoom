#!/usr/bin/env python3
"""Verify authorized raw HTTP attachment upload outside V1 JSON frames."""

from __future__ import annotations

import argparse
import http.client
import socket
import tempfile
import time
import uuid
from pathlib import Path

from v1_room_message_reliability_test import start_server, stop_server
from v1_smoke_test import (
    SmokeFailure, V1Client, data, find_port_range, login, register, require_success
)


def put(port: int, path: str, token: str, body: bytes, length: int | None = None) -> int:
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
    connection.request(
        "PUT",
        f"{path}?token={token}",
        body=body,
        headers={
            "Content-Type": "application/octet-stream",
            "Content-Length": str(len(body) if length is None else length),
        },
    )
    response = connection.getresponse()
    response.read()
    status = response.status
    connection.close()
    return status


def start_upload(
    client: V1Client, room_id: int, name: str, size: int,
    client_message_id: str | None = None,
) -> dict[str, object]:
    request: dict[str, object] = {"roomId": room_id, "fileName": name, "fileSize": size}
    if client_message_id:
        request["clientMessageId"] = client_message_id
    client.send("FILE_UPLOAD_START", request)
    payload = require_success(client.receive_type("FILE_UPLOAD_START_RSP"))
    if not payload.get("uploadId") or not payload.get("httpUploadPath"):
        raise SmokeFailure("upload start did not advertise an HTTP data-plane path")
    return payload


def start_friend_upload(
    client: V1Client, friend_username: str, name: str, size: int,
    client_message_id: str,
) -> dict[str, object]:
    client.send(
        "FRIEND_FILE_UPLOAD_START",
        {
            "friendUsername": friend_username,
            "fileName": name,
            "fileSize": size,
            "clientMessageId": client_message_id,
        },
    )
    payload = require_success(client.receive_type("FRIEND_FILE_UPLOAD_START_RSP"))
    if payload.get("clientMessageId") != client_message_id:
        raise SmokeFailure("friend upload start did not echo clientMessageId")
    return payload


def run_test(server: Path) -> None:
    port = find_port_range()
    http_port = port + 2
    password = "http-upload-password"
    suffix = uuid.uuid4().hex[:7]
    with tempfile.TemporaryDirectory(prefix="chat-http-upload-") as temp_name:
        directory = Path(temp_name)
        process = start_server(server, directory, directory / "http-upload.db", port)
        clients: list[V1Client] = []
        try:
            alice = V1Client("127.0.0.1", port, "http-alice")
            bob = V1Client("127.0.0.1", port, "http-bob")
            clients.extend([alice, bob])
            alice_name, bob_name = f"ha_{suffix}", f"hb_{suffix}"
            register(alice, alice_name, "HTTP Alice", password)
            register(bob, bob_name, "HTTP Bob", password)
            alice_login = login(alice, alice_name, password)
            bob_login = login(bob, bob_name, password)
            alice.send("CREATE_ROOM_REQ", {"roomName": "HTTP Upload Room"})
            room_id = require_success(alice.receive_type("CREATE_ROOM_RSP"))["roomId"]
            bob.send("JOIN_ROOM_REQ", {"roomId": room_id})
            require_success(bob.receive_type("JOIN_ROOM_RSP"))

            body = b"raw-http-file-bytes\x00without-base64"
            attachment_client_id = f"room-file-{uuid.uuid4()}"
            upload = start_upload(
                alice, room_id, "raw.bin", len(body), attachment_client_id)
            if upload.get("clientMessageId") != attachment_client_id:
                raise SmokeFailure("upload start did not echo clientMessageId")
            if put(http_port, str(upload["httpUploadPath"]), str(alice_login["fileToken"]), body) != 204:
                raise SmokeFailure("authorized exact HTTP upload failed")
            finalize = {
                "uploadId": upload["uploadId"],
                "clientMessageId": attachment_client_id,
            }
            alice.send("FILE_UPLOAD_END", finalize)
            match = lambda message: data(message).get("fileName") == "raw.bin"
            alice_notice = data(alice.receive_type("FILE_NOTIFY", predicate=match))
            bob.receive_type("FILE_NOTIFY", predicate=match)
            acceptance = require_success(
                alice.receive_type(
                    "FILE_UPLOAD_END_RSP",
                    predicate=lambda m: data(m).get("clientMessageId") == attachment_client_id,
                )
            )
            if acceptance.get("duplicate") is not False:
                raise SmokeFailure("first attachment finalization was not accepted as new")
            for field in ("id", "fileId", "sequence", "timestamp"):
                if acceptance.get(field) != alice_notice.get(field):
                    raise SmokeFailure(f"attachment ACK disagreed with notice field {field}")

            alice.send("FILE_UPLOAD_END", finalize)
            duplicate = require_success(
                alice.receive_type(
                    "FILE_UPLOAD_END_RSP",
                    predicate=lambda m: data(m).get("clientMessageId") == attachment_client_id,
                )
            )
            if duplicate.get("duplicate") is not True:
                raise SmokeFailure("attachment retry was not classified as duplicate")
            for field in ("id", "fileId", "sequence", "timestamp"):
                if duplicate.get(field) != acceptance.get(field):
                    raise SmokeFailure(f"duplicate ACK changed stable field {field}")
            if alice_notice.get("fileSize") != len(body) or "fileData" in alice_notice:
                raise SmokeFailure("attachment notification contained wrong metadata or inline bytes")
            if not isinstance(alice_notice.get("sequence"), int) or alice_notice["sequence"] <= 0:
                raise SmokeFailure("HTTP attachment notification omitted its durable sequence")
            if not isinstance(alice_notice.get("timestamp"), int) or alice_notice["timestamp"] <= 0:
                raise SmokeFailure("HTTP attachment notification omitted its authoritative timestamp")
            bob.send(
                "HISTORY_REQ",
                {
                    "roomId": room_id,
                    "count": 10,
                    "afterSequence": alice_notice["sequence"] - 1,
                },
            )
            history = require_success(bob.receive_type("HISTORY_RSP")).get("messages", [])
            history_copy = next(
                (
                    item
                    for item in history
                    if isinstance(item, dict) and item.get("id") == alice_notice.get("id")
                ),
                None,
            )
            if not isinstance(history_copy, dict) or history_copy.get("sequence") != alice_notice["sequence"]:
                raise SmokeFailure("live attachment sequence disagreed with sequence history")
            if history_copy.get("timestamp") != alice_notice["timestamp"]:
                raise SmokeFailure("live attachment timestamp disagreed with durable history")
            if history_copy.get("clientMessageId") != attachment_client_id:
                raise SmokeFailure("durable attachment omitted clientMessageId")

            conflicting = start_upload(
                alice, room_id, "conflict.bin", len(body), attachment_client_id)
            if put(http_port, str(conflicting["httpUploadPath"]), str(alice_login["fileToken"]), body) != 204:
                raise SmokeFailure("conflicting candidate upload failed before finalization")
            alice.send(
                "FILE_UPLOAD_END",
                {"uploadId": conflicting["uploadId"], "clientMessageId": attachment_client_id},
            )
            conflict = data(
                alice.receive_type(
                    "FILE_UPLOAD_END_RSP",
                    predicate=lambda m: data(m).get("uploadId") == conflicting["uploadId"],
                )
            )
            if conflict.get("success") is not False or conflict.get("errorCode") != "CLIENT_MESSAGE_ID_CONFLICT":
                raise SmokeFailure("clientMessageId reuse was not rejected as a conflict")

            alice.send("FRIEND_REQUEST_REQ", {"username": bob_name})
            require_success(alice.receive_type("FRIEND_REQUEST_RSP"))
            bob.receive_type(
                "FRIEND_REQUEST_NOTIFY",
                predicate=lambda m: data(m).get("fromUsername") == alice_name,
            )
            bob.send("FRIEND_PENDING_REQ")
            pending_requests = data(bob.receive_type("FRIEND_PENDING_RSP")).get("requests", [])
            friend_request = next(
                (item for item in pending_requests if item.get("fromUsername") == alice_name),
                None,
            )
            if not friend_request:
                raise SmokeFailure("friend request was not persisted for attachment test")
            bob.send(
                "FRIEND_ACCEPT_REQ",
                {"requestId": friend_request["requestId"], "fromUsername": alice_name},
            )
            require_success(bob.receive_type("FRIEND_ACCEPT_RSP"))
            alice.receive_type(
                "FRIEND_ACCEPT_NOTIFY",
                predicate=lambda m: data(m).get("acceptedBy") == bob_name,
            )

            friend_client_id = f"friend-file-{uuid.uuid4()}"
            friend_upload = start_friend_upload(
                alice, bob_name, "friend.bin", len(body), friend_client_id)
            if put(http_port, str(friend_upload["httpUploadPath"]), str(alice_login["fileToken"]), body) != 204:
                raise SmokeFailure("authorized friend HTTP upload failed")
            friend_finalize = {
                "uploadId": friend_upload["uploadId"],
                "clientMessageId": friend_client_id,
            }
            alice.send("FILE_UPLOAD_END", friend_finalize)
            alice.receive_type(
                "FRIEND_FILE_NOTIFY",
                predicate=lambda m: data(m).get("fileName") == "friend.bin",
            )
            bob.receive_type(
                "FRIEND_FILE_NOTIFY",
                predicate=lambda m: data(m).get("fileName") == "friend.bin",
            )
            friend_ack = require_success(
                alice.receive_type(
                    "FILE_UPLOAD_END_RSP",
                    predicate=lambda m: data(m).get("clientMessageId") == friend_client_id,
                )
            )
            if friend_ack.get("fileId", 0) >= 0 or friend_ack.get("duplicate") is not False:
                raise SmokeFailure("friend attachment ACK did not preserve signed file identity")
            alice.send("FILE_UPLOAD_END", friend_finalize)
            friend_duplicate = require_success(
                alice.receive_type(
                    "FILE_UPLOAD_END_RSP",
                    predicate=lambda m: data(m).get("clientMessageId") == friend_client_id,
                )
            )
            if friend_duplicate.get("duplicate") is not True or friend_duplicate.get("id") != friend_ack.get("id"):
                raise SmokeFailure("friend attachment retry changed durable identity")

            foreign = start_upload(alice, room_id, "foreign.bin", len(body))
            if put(http_port, str(foreign["httpUploadPath"]), str(bob_login["fileToken"]), body) != 403:
                raise SmokeFailure("foreign HTTP token operated another user's upload")
            if put(http_port, str(foreign["httpUploadPath"]), str(alice_login["fileToken"]), body) != 204:
                raise SmokeFailure("owner could not continue after foreign denial")
            alice.send("FILE_UPLOAD_END", {"uploadId": foreign["uploadId"]})
            alice.receive_type("FILE_NOTIFY", predicate=lambda m: data(m).get("fileName") == "foreign.bin")

            mismatch = start_upload(alice, room_id, "mismatch.bin", len(body))
            if put(http_port, str(mismatch["httpUploadPath"]), str(alice_login["fileToken"]), body, len(body) + 1) != 400:
                raise SmokeFailure("incorrect Content-Length was not rejected")
            alice.send("FILE_UPLOAD_CANCEL", {"uploadId": mismatch["uploadId"]})

            interrupted = start_upload(alice, room_id, "partial.bin", len(body))
            raw = socket.create_connection(("127.0.0.1", http_port), timeout=3)
            request = (
                f"PUT {interrupted['httpUploadPath']}?token={alice_login['fileToken']} HTTP/1.1\r\n"
                f"Host: 127.0.0.1\r\nContent-Length: {len(body)}\r\n\r\n"
            ).encode() + body[:4]
            raw.sendall(request)
            raw.close()
            time.sleep(0.1)
            status = put(http_port, str(interrupted["httpUploadPath"]), str(alice_login["fileToken"]), body)
            if status not in (403, 404):
                raise SmokeFailure("disconnected partial HTTP upload was not abandoned")

            for client in clients:
                client.close()
            clients.clear()
            stop_server(process)
            process = start_server(server, directory, directory / "http-upload.db", port)
            restarted_alice = V1Client("127.0.0.1", port, "http-alice-restarted")
            clients.append(restarted_alice)
            login(restarted_alice, alice_name, password)
            restarted_alice.send("FILE_UPLOAD_END", finalize)
            restart_ack = require_success(
                restarted_alice.receive_type(
                    "FILE_UPLOAD_END_RSP",
                    predicate=lambda m: data(m).get("clientMessageId") == attachment_client_id,
                )
            )
            if restart_ack.get("duplicate") is not True or restart_ack.get("id") != acceptance.get("id"):
                raise SmokeFailure("attachment retry after restart lost durable identity")
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
    except (OSError, SmokeFailure, KeyError, http.client.HTTPException) as exc:
        print(f"[v1-http-upload] FAIL: {exc}")
        return 1
    print("[v1-http-upload] PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
