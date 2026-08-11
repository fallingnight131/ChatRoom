#!/usr/bin/env python3
"""Rehearse a timed V1 identity backup, restore, login, and history check."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
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


def start_server(
    server: Path, database: Path, working_directory: Path
) -> tuple[subprocess.Popen[str], int]:
    port = find_port_range()
    environment = os.environ.copy()
    environment["CHATROOM_DB_PATH"] = str(database)
    environment["CHATROOM_DEVELOPER_KEY"] = "m3-restore-rehearsal-key"
    process = subprocess.Popen(
        [str(server), "--port", str(port)],
        cwd=working_directory,
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    try:
        wait_for_server(process, port)
    except Exception:
        stop_server(process)
        raise
    return process, port


def stop_server(process: subprocess.Popen[str]) -> None:
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)
            raise SmokeFailure("server did not stop within five seconds")


def server_output(process: subprocess.Popen[str]) -> str:
    return process.stdout.read() if process.stdout else ""


def run_migration(migration_cli: Path, arguments: list[str]) -> str:
    result = subprocess.run(
        [str(migration_cli), *arguments],
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    if result.returncode != 0:
        safe_status = result.stderr.strip().replace("\n", "; ")
        raise SmokeFailure(
            f"migration command failed with {result.returncode}: {safe_status}"
        )
    return result.stdout


def output_value(output: str, key: str) -> str:
    prefix = f"{key}="
    values = [
        line[len(prefix):]
        for line in output.splitlines()
        if line.startswith(prefix)
    ]
    if len(values) != 1 or not values[0]:
        raise SmokeFailure(f"migration output omitted {key}")
    return values[0]


def insert_legacy_account(database: Path, username: str, password: str) -> None:
    salt = "m3-restore-legacy-salt"
    digest = hashlib.sha256((password + salt).encode("utf-8")).hexdigest()
    with sqlite3.connect(database) as connection:
        connection.execute(
            "INSERT INTO users (username, display_name, password_hash, salt) "
            "VALUES (?, ?, ?, ?)",
            (username, "Legacy Restore User", digest, salt),
        )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_evidence(path: Path, evidence: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    temporary.replace(path)


def rehearse(server: Path, migration_cli: Path, evidence_path: Path) -> None:
    modern_username = "m3_restore_modern"
    modern_password = "m3-modern-password"
    legacy_username = "m3_restore_legacy"
    legacy_password = "m3-legacy-password"
    message_text = "m3-restore-history-proof"

    with tempfile.TemporaryDirectory(prefix="chat-room-v1-restore-") as temp_name:
        temporary = Path(temp_name)
        source = temporary / "source.db"
        backup = temporary / "backup.db"
        proof = temporary / "backup-proof.properties"
        restored = temporary / "restored.db"
        source_process: subprocess.Popen[str] | None = None
        restored_process: subprocess.Popen[str] | None = None
        clients: list[V1Client] = []
        try:
            source_process, source_port = start_server(server, source, temporary)
            source_client = V1Client("127.0.0.1", source_port, "source-modern")
            clients.append(source_client)
            register(source_client, modern_username, "Modern Restore User", modern_password)
            login(source_client, modern_username, modern_password)
            source_client.send("CREATE_ROOM_REQ", {"roomName": "M3 Restore Room"})
            room = require_success(source_client.receive_type("CREATE_ROOM_RSP"))
            room_id = room.get("roomId")
            if not isinstance(room_id, int) or room_id <= 0:
                raise SmokeFailure("source server returned an invalid room id")
            source_client.send(
                "CHAT_MSG",
                {"roomId": room_id, "content": message_text, "contentType": "text"},
            )
            source_client.receive_type("CHAT_MSG", predicate=lambda message: (
                data(message).get("content") == message_text
            ))
            source_client.close()
            clients.remove(source_client)
            stop_started = time.monotonic()
            stop_server(source_process)
            source_stop_seconds = time.monotonic() - stop_started
            source_process = None

            insert_legacy_account(source, legacy_username, legacy_password)
            backup_started = time.monotonic()
            backup_output = run_migration(
                migration_cli, ["backup", str(source), str(backup), str(proof)]
            )
            backup_seconds = time.monotonic() - backup_started
            fingerprint = output_value(backup_output, "source_fingerprint_sha256")
            if output_value(backup_output, "identity_rows") != "2":
                raise SmokeFailure("backup did not contain both credential generations")

            restore_started = time.monotonic()
            shutil.copy2(backup, restored)
            verify_output = run_migration(
                migration_cli, ["verify-backup", str(restored), str(proof)]
            )
            if "status=BACKUP_VERIFIED" not in verify_output:
                raise SmokeFailure("restored backup was not verified")
            final_output = run_migration(
                migration_cli,
                ["verify-final", str(source), str(restored), str(proof), fingerprint],
            )
            if "status=FINAL_INPUT_VERIFIED" not in final_output:
                raise SmokeFailure("final restore input was not verified")

            restored_process, restored_port = start_server(server, restored, temporary)
            modern_client = V1Client("127.0.0.1", restored_port, "restored-modern")
            legacy_client = V1Client("127.0.0.1", restored_port, "restored-legacy")
            clients.extend([modern_client, legacy_client])
            login(modern_client, modern_username, modern_password)
            login(legacy_client, legacy_username, legacy_password)
            modern_client.send("HISTORY_REQ", {"roomId": room_id, "count": 20})
            history = data(modern_client.receive_type("HISTORY_RSP")).get("messages")
            if not isinstance(history, list) or not any(
                isinstance(message, dict) and message.get("content") == message_text
                for message in history
            ):
                raise SmokeFailure("restored server history omitted the source message")
            for client in list(clients):
                client.close()
                clients.remove(client)
            stop_server(restored_process)
            restored_process = None
            restore_seconds = time.monotonic() - restore_started

            evidence = {
                "schema_version": 1,
                "recorded_at_utc": datetime.now(timezone.utc).isoformat(),
                "platform": platform.platform(),
                "server_sha256": sha256_file(server),
                "source_fingerprint_sha256": fingerprint,
                "identity_rows": 2,
                "credential_generations_verified": ["ARGON2ID", "V1_SHA256"],
                "history_verified": True,
                "controlled_source_process_stopped": True,
                "production_writer_quiescence_verified": False,
                "source_stop_seconds": round(source_stop_seconds, 6),
                "backup_seconds": round(backup_seconds, 6),
                "restore_verify_launch_login_seconds": round(restore_seconds, 6),
            }
            write_evidence(evidence_path, evidence)
            print(
                "[V1IdentityRestoreRehearsal] PASS: verified backup, restored both "
                f"credential generations and history in {restore_seconds:.3f}s"
            )
            print(f"[V1IdentityRestoreRehearsal] evidence={evidence_path}")
        except Exception:
            outputs: list[str] = []
            for process in (source_process, restored_process):
                if process is not None:
                    try:
                        stop_server(process)
                    finally:
                        output = server_output(process)
                        if output:
                            outputs.append(output)
            if outputs:
                print(
                    "\n[V1IdentityRestoreRehearsal] server output:\n"
                    + "\n".join(outputs),
                    file=sys.stderr,
                )
            raise
        finally:
            for client in clients:
                client.close()
            for process in (source_process, restored_process):
                if process is not None:
                    stop_server(process)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--migration-cli", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    server = args.server.resolve()
    migration_cli = args.migration_cli.resolve()
    if not server.is_file() or not migration_cli.is_file():
        print("server or migration CLI executable does not exist", file=sys.stderr)
        return 2
    try:
        rehearse(server, migration_cli, args.evidence.resolve())
        return 0
    except (
        OSError,
        SmokeFailure,
        subprocess.SubprocessError,
        sqlite3.Error,
        json.JSONDecodeError,
    ) as error:
        print(f"[V1IdentityRestoreRehearsal] FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
