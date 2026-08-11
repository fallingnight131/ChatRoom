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


def start_upload(client: V1Client, room_id: int, name: str, size: int) -> dict[str, object]:
    client.send("FILE_UPLOAD_START", {"roomId": room_id, "fileName": name, "fileSize": size})
    payload = require_success(client.receive_type("FILE_UPLOAD_START_RSP"))
    if not payload.get("uploadId") or not payload.get("httpUploadPath"):
        raise SmokeFailure("upload start did not advertise an HTTP data-plane path")
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
            upload = start_upload(alice, room_id, "raw.bin", len(body))
            if put(http_port, str(upload["httpUploadPath"]), str(alice_login["fileToken"]), body) != 204:
                raise SmokeFailure("authorized exact HTTP upload failed")
            alice.send("FILE_UPLOAD_END", {"uploadId": upload["uploadId"]})
            match = lambda message: data(message).get("fileName") == "raw.bin"
            alice_notice = data(alice.receive_type("FILE_NOTIFY", predicate=match))
            bob.receive_type("FILE_NOTIFY", predicate=match)
            if alice_notice.get("fileSize") != len(body) or "fileData" in alice_notice:
                raise SmokeFailure("attachment notification contained wrong metadata or inline bytes")

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
