#!/usr/bin/env python3
"""Run the Java V2 PostgreSQL messaging baseline in an isolated local cluster."""

from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import socket
import subprocess
import tempfile
import threading
from datetime import datetime, timezone
from pathlib import Path

from java_performance_result import validate


ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "Backend"
TEST_USER = "chatroom_performance"
TEST_DATABASE = "chatroom_performance"


def required_command(name: str) -> str:
    command = shutil.which(name)
    if command:
        return command
    pg_config = shutil.which("pg_config")
    if pg_config:
        bindir = subprocess.run(
            [pg_config, "--bindir"], check=True, capture_output=True, text=True
        ).stdout.strip()
        candidate = Path(bindir) / name
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError(f"required command not found: {name}")


def available_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def run(command: list[str], cwd: Path, env: dict[str, str] | None = None) -> None:
    print(f"[JavaPerformance] {' '.join(command)}")
    subprocess.run(command, cwd=cwd, env=env, check=True)


class ProcessSampler:
    def __init__(self, pid: int) -> None:
        self.pid = pid
        self.peak_rss_bytes = 0
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._loop, daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=2)
        self._sample()

    def _loop(self) -> None:
        while not self._stop.wait(0.02):
            self._sample()

    def _sample(self) -> None:
        if os.name == "nt":
            return
        completed = subprocess.run(
            ["ps", "-o", "rss=", "-p", str(self.pid)],
            capture_output=True,
            text=True,
            check=False,
        )
        try:
            rss_kib = int(completed.stdout.strip())
        except ValueError:
            return
        self.peak_rss_bytes = max(self.peak_rss_bytes, rss_kib * 1024)


def source_revision() -> str:
    completed = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True
    )
    return completed.stdout.strip()


def worktree_dirty() -> bool:
    completed = subprocess.run(
        ["git", "status", "--porcelain"], cwd=ROOT,
        capture_output=True, text=True, check=True
    )
    return bool(completed.stdout.strip())


def enrich(raw: Path, output: Path, java_rss: int, postgres_rss: int) -> None:
    result = json.loads(raw.read_text(encoding="utf-8"))
    result["recordedAt"] = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    result["sourceRevision"] = source_revision()
    result["worktreeDirty"] = worktree_dirty()
    result["host"] = {
        "platform": platform.platform(),
        "pythonVersion": platform.python_version(),
        "javaPeakRssBytes": java_rss,
        "postgresPostmasterPeakRssBytes": postgres_rss,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(
        json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    temporary.replace(output)
    validate(result, result["sourceRevision"])


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument("--output", type=Path, required=True)
    value.add_argument("--warmup", type=int, default=100)
    value.add_argument("--append", type=int, default=500)
    value.add_argument("--retry", type=int, default=200)
    value.add_argument("--concurrent", type=int, default=500)
    value.add_argument("--concurrency", type=int, default=8)
    value.add_argument("--history", type=int, default=200)
    value.add_argument("--payload-bytes", type=int, default=256)
    return value


def require_boundary_rejections(executable: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="chat-java-performance-boundary-", dir="/tmp") as name:
        unused_output = Path(name) / "unused.json"
        arguments = [
            str(executable), "--jdbc-url", "jdbc:postgresql://database.example.test/chat",
            "--username", TEST_USER, "--password", "", "--output", str(unused_output),
            "--warmup", "0", "--append", "1", "--retry", "1", "--concurrent", "1",
            "--concurrency", "1", "--history", "1", "--payload-bytes", "1",
        ]
        environment = os.environ.copy()
        environment.pop("CHATROOM_PERFORMANCE_CONFIRM", None)
        missing_confirmation = subprocess.run(
            arguments, cwd=BACKEND, env=environment, capture_output=True, text=True,
            check=False
        )
        if (missing_confirmation.returncode == 0
                or "CHATROOM_PERFORMANCE_CONFIRM must be DISPOSABLE_POSTGRES_ONLY"
                not in missing_confirmation.stderr):
            raise RuntimeError("performance executable did not reject missing confirmation")
        environment["CHATROOM_PERFORMANCE_CONFIRM"] = "DISPOSABLE_POSTGRES_ONLY"
        remote_database = subprocess.run(
            arguments, cwd=BACKEND, env=environment, capture_output=True, text=True,
            check=False
        )
        if (remote_database.returncode == 0
                or "performance baseline requires loopback PostgreSQL"
                not in remote_database.stderr):
            raise RuntimeError("performance executable did not reject a remote PostgreSQL host")
        if unused_output.exists():
            raise RuntimeError("rejected performance boundary wrote an output file")


def main() -> int:
    args = parser().parse_args()
    initdb = required_command("initdb")
    pg_ctl = required_command("pg_ctl")
    createdb = required_command("createdb")
    wrapper = BACKEND / ("gradlew.bat" if os.name == "nt" else "gradlew")
    run([str(wrapper), "--no-daemon", ":performance-baseline:installDist"], BACKEND)
    executable = BACKEND / "performance-baseline/build/install/performance-baseline/bin"
    executable /= "performance-baseline.bat" if os.name == "nt" else "performance-baseline"
    if not executable.is_file():
        raise RuntimeError("performance baseline executable was not installed")
    require_boundary_rejections(executable)

    with tempfile.TemporaryDirectory(prefix="chat-java-performance-", dir="/tmp") as name:
        root = Path(name)
        data = root / "data"
        socket_dir = root / "socket"
        socket_dir.mkdir()
        log = root / "postgres.log"
        raw = root / "raw.json"
        port = available_port()
        started = False
        run([
            initdb, "-D", str(data), "--username", TEST_USER, "--auth", "trust",
            "--encoding", "UTF8", "--locale", "C", "--no-sync",
        ], ROOT)
        try:
            run([
                pg_ctl, "-D", str(data), "-l", str(log), "-o",
                f"-h 127.0.0.1 -p {port} -k {socket_dir}", "-w", "start",
            ], ROOT)
            started = True
            run([
                createdb, "-h", "127.0.0.1", "-p", str(port), "-U", TEST_USER,
                TEST_DATABASE,
            ], ROOT)
            postmaster_pid = int((data / "postmaster.pid").read_text(encoding="utf-8").splitlines()[0])
            postgres_sampler = ProcessSampler(postmaster_pid)
            postgres_sampler.start()
            environment = os.environ.copy()
            environment["CHATROOM_PERFORMANCE_CONFIRM"] = "DISPOSABLE_POSTGRES_ONLY"
            command = [
                str(executable),
                "--jdbc-url", f"jdbc:postgresql://127.0.0.1:{port}/{TEST_DATABASE}",
                "--username", TEST_USER,
                "--password", "",
                "--output", str(raw),
                "--warmup", str(args.warmup),
                "--append", str(args.append),
                "--retry", str(args.retry),
                "--concurrent", str(args.concurrent),
                "--concurrency", str(args.concurrency),
                "--history", str(args.history),
                "--payload-bytes", str(args.payload_bytes),
            ]
            print(f"[JavaPerformance] {executable.name} <bounded disposable scenario>")
            process = subprocess.Popen(command, cwd=BACKEND, env=environment)
            java_sampler = ProcessSampler(process.pid)
            java_sampler.start()
            return_code = process.wait()
            java_sampler.stop()
            postgres_sampler.stop()
            if return_code != 0:
                raise subprocess.CalledProcessError(return_code, command)
            enrich(raw, args.output.resolve(), java_sampler.peak_rss_bytes,
                   postgres_sampler.peak_rss_bytes)
            print(f"[JavaPerformance] wrote {args.output.resolve()}")
        finally:
            if started:
                run([pg_ctl, "-D", str(data), "-m", "fast", "-w", "stop"], ROOT)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[JavaPerformance] verification failed: {error}")
        raise SystemExit(1)
