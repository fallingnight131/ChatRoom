#!/usr/bin/env python3
"""Run V2 migrations against a disposable local PostgreSQL cluster."""

from __future__ import annotations

import os
import shutil
import socket
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "Backend"
TEST_USER = "chatroom_test"
TEST_DATABASE = "chatroom_test"


def required_command(name: str) -> str:
    command = shutil.which(name)
    if command:
        return command
    pg_config = shutil.which("pg_config")
    if pg_config:
        bindir = subprocess.run(
            [pg_config, "--bindir"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        candidate = Path(bindir) / name
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError(f"required PostgreSQL command not found: {name}")


def run(command: list[str], cwd: Path, env: dict[str, str] | None = None) -> None:
    print(f"[PostgreSQL] {' '.join(command)}")
    subprocess.run(command, cwd=cwd, env=env, check=True)


def available_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def main() -> int:
    initdb = required_command("initdb")
    pg_ctl = required_command("pg_ctl")
    createdb = required_command("createdb")
    wrapper = BACKEND / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        raise RuntimeError(f"Gradle wrapper not found: {wrapper}")

    with tempfile.TemporaryDirectory(prefix="chat-pg-", dir="/tmp") as temporary:
        root = Path(temporary)
        data = root / "data"
        socket_dir = root / "socket"
        socket_dir.mkdir()
        log = root / "postgres.log"
        port = available_port()
        started = False
        run([
            initdb,
            "-D",
            str(data),
            "--username",
            TEST_USER,
            "--auth",
            "trust",
            "--encoding",
            "UTF8",
            "--locale",
            "C",
            "--no-sync",
        ], ROOT)
        try:
            run([
                pg_ctl,
                "-D",
                str(data),
                "-l",
                str(log),
                "-o",
                f"-h 127.0.0.1 -p {port} -k {socket_dir}",
                "-w",
                "start",
            ], ROOT)
            started = True
            run([
                createdb,
                "-h",
                "127.0.0.1",
                "-p",
                str(port),
                "-U",
                TEST_USER,
                TEST_DATABASE,
            ], ROOT)
            environment = os.environ.copy()
            environment.update({
                "CHATROOM_TEST_POSTGRES_URL":
                    f"jdbc:postgresql://127.0.0.1:{port}/{TEST_DATABASE}",
                "CHATROOM_TEST_POSTGRES_USER": TEST_USER,
                "CHATROOM_TEST_POSTGRES_PASSWORD": "",
            })
            run([
                str(wrapper),
                "--no-daemon",
                ":persistence-postgres:test",
                "--rerun-tasks",
            ], BACKEND, environment)
        finally:
            if started:
                run([pg_ctl, "-D", str(data), "-m", "fast", "-w", "stop"], ROOT)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[PostgreSQL] verification failed: {error}")
        raise SystemExit(1)
