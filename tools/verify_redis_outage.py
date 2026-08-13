#!/usr/bin/env python3
"""Verify product readiness and durable delivery across a real Redis restart."""

from __future__ import annotations

import os
import shutil
import signal
import socket
import subprocess
import tempfile
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "Backend"
TEST_USER = "chatroom_redis_outage"
TEST_DATABASE = "chatroom_redis_outage"


def required(name: str) -> str:
    value = shutil.which(name)
    if value:
        return value
    pg_config = shutil.which("pg_config")
    if pg_config:
        directory = subprocess.run(
            [pg_config, "--bindir"], capture_output=True, text=True,
            check=True).stdout.strip()
        candidate = Path(directory) / name
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError(f"required command not found: {name}")


def available_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def run(command: list[str], cwd: Path) -> None:
    print(f"[Redis outage] {' '.join(command)}")
    subprocess.run(command, cwd=cwd, check=True)


def stop_redis(pidfile: Path) -> None:
    if not pidfile.is_file():
        raise RuntimeError("disposable Redis pidfile is missing")
    pid = int(pidfile.read_text(encoding="utf-8").strip())
    print("[Redis outage] stop disposable Redis")
    os.kill(pid, signal.SIGTERM)
    for _ in range(200):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return
        time.sleep(0.025)
    os.kill(pid, signal.SIGKILL)
    for _ in range(200):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return
        time.sleep(0.025)
    raise RuntimeError("disposable Redis process did not stop")


def stop_process(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def main() -> int:
    initdb = required("initdb")
    pg_ctl = required("pg_ctl")
    createdb = required("createdb")
    redis_server = required("redis-server")
    wrapper = BACKEND / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        raise RuntimeError(f"Gradle wrapper not found: {wrapper}")

    with tempfile.TemporaryDirectory(prefix="chat-redis-outage-", dir="/tmp") as value:
        root = Path(value)
        data = root / "postgres-data"
        socket_dir = root / "postgres-socket"
        socket_dir.mkdir()
        postgres_log = root / "postgres.log"
        redis_log = root / "redis.log"
        redis_pidfile = root / "redis.pid"
        redis_config = root / "redis.conf"
        postgres_port = available_port()
        redis_port = available_port()
        redis_config.write_text("\n".join([
            "bind 127.0.0.1",
            f"port {redis_port}",
            "save \"\"",
            "appendonly no",
            f"dir {root}",
            f"pidfile {redis_pidfile}",
            f"logfile {redis_log}",
            "daemonize yes",
        ]) + "\n", encoding="utf-8")

        postgres_started = False
        redis_started = False
        process: subprocess.Popen[bytes] | None = None
        run([initdb, "-D", str(data), "--username", TEST_USER,
             "--auth", "trust", "--encoding", "UTF8", "--locale", "C",
             "--no-sync"], ROOT)
        try:
            run([pg_ctl, "-D", str(data), "-l", str(postgres_log), "-o",
                 f"-h 127.0.0.1 -p {postgres_port} -k {socket_dir}",
                 "-w", "start"], ROOT)
            postgres_started = True
            run([createdb, "-h", "127.0.0.1", "-p", str(postgres_port),
                 "-U", TEST_USER, TEST_DATABASE], ROOT)
            run([redis_server, str(redis_config)], ROOT)
            redis_started = True

            environment = os.environ.copy()
            environment.update({
                "CHATROOM_TEST_POSTGRES_URL":
                    f"jdbc:postgresql://127.0.0.1:{postgres_port}/{TEST_DATABASE}",
                "CHATROOM_TEST_POSTGRES_USER": TEST_USER,
                "CHATROOM_TEST_POSTGRES_PASSWORD": "",
                "CHATROOM_TEST_REDIS_URI": f"redis://127.0.0.1:{redis_port}/0",
                "CHATROOM_TEST_REDIS_CONTROL_DIR": str(root),
            })
            command = [
                str(wrapper), "--no-daemon", "--no-configuration-cache",
                ":im-gateway:test", "--tests",
                "*GatewayRuntimePostgresIntegrationTest."
                "withdrawsReadinessAndConvergesDurableMessageAcrossRedisRestart",
                "--rerun-tasks",
            ]
            print("[Redis outage] running product runtime failure gate")
            process = subprocess.Popen(command, cwd=BACKEND, env=environment)
            stop_request = root / "redis-stop-request"
            stopped_marker = root / "redis-stopped"
            start_request = root / "redis-start-request"
            started_marker = root / "redis-started"
            while process.poll() is None:
                if (stop_request.is_file() and not stopped_marker.is_file()
                        and redis_started):
                    stop_redis(redis_pidfile)
                    redis_started = False
                    stopped_marker.write_text("stopped\n", encoding="utf-8")
                if (start_request.is_file() and stopped_marker.is_file()
                        and not started_marker.is_file() and not redis_started):
                    run([redis_server, str(redis_config)], ROOT)
                    redis_started = True
                    started_marker.write_text("started\n", encoding="utf-8")
                time.sleep(0.02)
            if process.returncode != 0:
                raise subprocess.CalledProcessError(process.returncode, command)
        finally:
            if process is not None:
                stop_process(process)
            if redis_started:
                stop_redis(redis_pidfile)
            if postgres_started:
                run([pg_ctl, "-D", str(data), "-m", "fast", "-w", "stop"], ROOT)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[Redis outage] verification failed: {error}")
        raise SystemExit(1)
