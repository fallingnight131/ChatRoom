#!/usr/bin/env python3
"""Run the Java V2 production gateway baseline in an isolated local environment."""

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
import time
from datetime import datetime, timezone
from pathlib import Path

from java_gateway_performance_result import validate


ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "Backend"
TEST_USER = "chatroom_gateway_performance"
TEST_DATABASE = "chatroom_gateway_performance"


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


def available_ports(count: int) -> list[int]:
    probes: list[socket.socket] = []
    try:
        for _ in range(count):
            probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            probe.bind(("127.0.0.1", 0))
            probes.append(probe)
        return [int(probe.getsockname()[1]) for probe in probes]
    finally:
        for probe in probes:
            probe.close()


def run(command: list[str], cwd: Path, env: dict[str, str] | None = None) -> None:
    print(f"[GatewayPerformance] {' '.join(command)}")
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
            capture_output=True, text=True, check=False
        )
        try:
            rss_kib = int(completed.stdout.strip())
        except ValueError:
            return
        self.peak_rss_bytes = max(self.peak_rss_bytes, rss_kib * 1024)


def source_revision() -> str:
    return subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=ROOT,
        capture_output=True, text=True, check=True
    ).stdout.strip()


def worktree_dirty() -> bool:
    return bool(subprocess.run(
        ["git", "status", "--porcelain"], cwd=ROOT,
        capture_output=True, text=True, check=True
    ).stdout.strip())


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


def generate_certificate(openssl: str, root: Path) -> tuple[Path, Path]:
    certificate = root / "certificate.pem"
    private_key = root / "private-key.pem"
    command = [
        openssl, "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-nodes",
        "-keyout", str(private_key), "-out", str(certificate), "-days", "1",
        "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
    ]
    print("[GatewayPerformance] openssl req <ephemeral localhost certificate>")
    subprocess.run(command, cwd=root, check=True, capture_output=True, text=True)
    return certificate, private_key


def executable_arguments(
    executable: Path, jdbc_url: str, certificate: Path, private_key: Path,
    gateway_port: int, admin_port: int, output: Path, control_dir: Path,
    args: argparse.Namespace,
) -> list[str]:
    return [
        str(executable), "--jdbc-url", jdbc_url,
        "--username", TEST_USER, "--password", "",
        "--certificate", str(certificate), "--private-key", str(private_key),
        "--gateway-port", str(gateway_port), "--admin-port", str(admin_port),
        "--output", str(output), "--warmup", str(args.warmup),
        "--messages", str(args.messages), "--payload-bytes", str(args.payload_bytes),
        "--receivers", str(args.receivers),
        "--active-conversations", str(args.active_conversations),
        "--reconnect-rounds", str(args.reconnect_rounds),
        "--reconnect-batch-size", str(args.reconnect_batch_size),
        "--reconnect-batch-interval-millis", str(args.reconnect_batch_interval_millis),
        "--slow-consumer-max-messages", str(args.slow_consumer_max_messages),
        "--postgres-saturation-senders", str(args.postgres_saturation_senders),
        "--postgres-outage", "1" if args.postgres_outage else "0",
        "--postgres-outage-control-dir", str(control_dir),
    ]


def require_boundary_rejections(
    executable: Path, certificate: Path, private_key: Path
) -> None:
    with tempfile.TemporaryDirectory(
        prefix="chat-gateway-performance-boundary-", dir="/tmp"
    ) as name:
        unused = Path(name) / "unused.json"
        boundary = argparse.Namespace(
            warmup=0, messages=1, payload_bytes=1, receivers=1, reconnect_rounds=0,
            reconnect_batch_size=0, reconnect_batch_interval_millis=0,
            slow_consumer_max_messages=0, postgres_saturation_senders=0,
            postgres_outage=False, active_conversations=1)
        arguments = executable_arguments(
            executable, "jdbc:postgresql://database.example.test/chat",
            certificate, private_key, 9443, 9090, unused, Path(name), boundary)
        environment = os.environ.copy()
        environment.pop("CHATROOM_PERFORMANCE_CONFIRM", None)
        missing_confirmation = subprocess.run(
            arguments, cwd=BACKEND, env=environment,
            capture_output=True, text=True, check=False
        )
        if (missing_confirmation.returncode == 0
                or "CHATROOM_PERFORMANCE_CONFIRM must be DISPOSABLE_POSTGRES_ONLY"
                not in missing_confirmation.stderr):
            raise RuntimeError("gateway baseline did not reject missing confirmation")
        environment["CHATROOM_PERFORMANCE_CONFIRM"] = "DISPOSABLE_POSTGRES_ONLY"
        remote_database = subprocess.run(
            arguments, cwd=BACKEND, env=environment,
            capture_output=True, text=True, check=False
        )
        if (remote_database.returncode == 0
                or "gateway baseline requires loopback PostgreSQL"
                not in remote_database.stderr):
            raise RuntimeError("gateway baseline did not reject remote PostgreSQL")
        if unused.exists():
            raise RuntimeError("rejected gateway boundary wrote an output file")
        boundary.reconnect_batch_size = 1
        boundary.reconnect_batch_interval_millis = 50
        invalid_pacing_arguments = executable_arguments(
            executable, "jdbc:postgresql://127.0.0.1:5432/chat",
            certificate, private_key, 9443, 9090, unused, Path(name), boundary)
        invalid_pacing = subprocess.run(
            invalid_pacing_arguments, cwd=BACKEND, env=environment,
            capture_output=True, text=True, check=False
        )
        if (invalid_pacing.returncode == 0
                or "paced reconnect requires rounds and at least two batches"
                not in invalid_pacing.stderr):
            raise RuntimeError("gateway baseline accepted pacing without reconnect rounds")
        if unused.exists():
            raise RuntimeError("rejected reconnect pacing wrote an output file")


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument("--output", type=Path, required=True)
    value.add_argument("--warmup", type=int, default=20)
    value.add_argument("--messages", type=int, default=200)
    value.add_argument("--payload-bytes", type=int, default=256)
    value.add_argument("--receivers", type=int, default=1)
    value.add_argument("--active-conversations", type=int, default=1)
    value.add_argument("--reconnect-rounds", type=int, default=0)
    value.add_argument("--reconnect-batch-size", type=int, default=0)
    value.add_argument("--reconnect-batch-interval-millis", type=int, default=0)
    value.add_argument("--slow-consumer-max-messages", type=int, default=0)
    value.add_argument("--postgres-saturation-senders", type=int, default=0)
    value.add_argument("--postgres-outage", action="store_true")
    return value


def main() -> int:
    args = parser().parse_args()
    initdb = required_command("initdb")
    pg_ctl = required_command("pg_ctl")
    createdb = required_command("createdb")
    openssl = required_command("openssl")
    wrapper = BACKEND / ("gradlew.bat" if os.name == "nt" else "gradlew")
    run([str(wrapper), "--no-daemon", ":gateway-performance-baseline:installDist"], BACKEND)
    executable = BACKEND / "gateway-performance-baseline/build/install"
    executable /= "gateway-performance-baseline/bin"
    executable /= ("gateway-performance-baseline.bat" if os.name == "nt"
                   else "gateway-performance-baseline")
    if not executable.is_file():
        raise RuntimeError("gateway performance executable was not installed")

    with tempfile.TemporaryDirectory(prefix="chat-gateway-performance-", dir="/tmp") as name:
        root = Path(name)
        data = root / "data"
        socket_dir = root / "socket"
        socket_dir.mkdir()
        log = root / "postgres.log"
        raw = root / "raw.json"
        certificate, private_key = generate_certificate(openssl, root)
        require_boundary_rejections(executable, certificate, private_key)
        postgres_port, gateway_port, admin_port = available_ports(3)
        started = False
        run([
            initdb, "-D", str(data), "--username", TEST_USER, "--auth", "trust",
            "--encoding", "UTF8", "--locale", "C", "--no-sync",
        ], ROOT)
        try:
            run([
                pg_ctl, "-D", str(data), "-l", str(log), "-o",
                f"-h 127.0.0.1 -p {postgres_port} -k {socket_dir}", "-w", "start",
            ], ROOT)
            started = True
            run([
                createdb, "-h", "127.0.0.1", "-p", str(postgres_port),
                "-U", TEST_USER, TEST_DATABASE,
            ], ROOT)
            postmaster_pid = int((data / "postmaster.pid")
                                 .read_text(encoding="utf-8").splitlines()[0])
            postgres_sampler = ProcessSampler(postmaster_pid)
            postgres_sampler.start()
            environment = os.environ.copy()
            environment["CHATROOM_PERFORMANCE_CONFIRM"] = "DISPOSABLE_POSTGRES_ONLY"
            command = executable_arguments(
                executable,
                f"jdbc:postgresql://127.0.0.1:{postgres_port}/{TEST_DATABASE}",
                certificate, private_key, gateway_port, admin_port, raw, root, args)
            print("[GatewayPerformance] gateway-performance-baseline <bounded loopback scenario>")
            process = subprocess.Popen(command, cwd=BACKEND, env=environment)
            java_sampler = ProcessSampler(process.pid)
            java_sampler.start()
            postgres_peak_rss = 0
            stop_request = root / "postgres-stop-request"
            stopped_marker = root / "postgres-stopped"
            start_request = root / "postgres-start-request"
            started_marker = root / "postgres-started"
            while process.poll() is None:
                if (args.postgres_outage and stop_request.exists()
                        and not stopped_marker.exists()):
                    postgres_sampler.stop()
                    postgres_peak_rss = max(
                        postgres_peak_rss, postgres_sampler.peak_rss_bytes)
                    run([pg_ctl, "-D", str(data), "-m", "fast", "-w", "stop"], ROOT)
                    started = False
                    stopped_marker.write_text("stopped\n", encoding="utf-8")
                if (args.postgres_outage and start_request.exists()
                        and not started_marker.exists()):
                    run([
                        pg_ctl, "-D", str(data), "-l", str(log), "-o",
                        f"-h 127.0.0.1 -p {postgres_port} -k {socket_dir}",
                        "-w", "start",
                    ], ROOT)
                    started = True
                    postmaster_pid = int((data / "postmaster.pid")
                                         .read_text(encoding="utf-8").splitlines()[0])
                    postgres_sampler = ProcessSampler(postmaster_pid)
                    postgres_sampler.start()
                    started_marker.write_text("started\n", encoding="utf-8")
                time.sleep(0.02)
            return_code = process.returncode
            java_sampler.stop()
            postgres_sampler.stop()
            postgres_peak_rss = max(
                postgres_peak_rss, postgres_sampler.peak_rss_bytes)
            if return_code != 0:
                raise subprocess.CalledProcessError(return_code, command)
            enrich(raw, args.output.resolve(), java_sampler.peak_rss_bytes,
                   postgres_peak_rss)
            print(f"[GatewayPerformance] wrote {args.output.resolve()}")
        finally:
            if started:
                run([pg_ctl, "-D", str(data), "-m", "fast", "-w", "stop"], ROOT)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[GatewayPerformance] verification failed: {error}")
        raise SystemExit(1)
