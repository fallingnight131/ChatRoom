#!/usr/bin/env python3
"""Measure a repeatable V1 room-message baseline against the real server."""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import subprocess
import sys
import tempfile
import threading
import time
import uuid
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


ROOT = Path(__file__).resolve().parents[1]
SQLITE_METRIC = re.compile(r"\[M0_METRIC\] sqlite_save_us=([0-9.]+)")


def percentile(samples: list[float], percentage: float) -> float:
    if not samples:
        raise SmokeFailure("cannot calculate a percentile without samples")
    ordered = sorted(samples)
    position = (len(ordered) - 1) * percentage
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def distribution(samples: list[float]) -> dict[str, float]:
    return {
        "min": round(min(samples), 3),
        "p50": round(percentile(samples, 0.50), 3),
        "p95": round(percentile(samples, 0.95), 3),
        "p99": round(percentile(samples, 0.99), 3),
        "max": round(max(samples), 3),
        "mean": round(sum(samples) / len(samples), 3),
    }


def parse_cpu_time(value: str) -> float:
    day_parts = value.strip().split("-")
    days = int(day_parts[0]) if len(day_parts) == 2 else 0
    clock = day_parts[-1].split(":")
    if len(clock) == 2:
        hours = 0
        minutes, seconds = clock
    elif len(clock) == 3:
        hours, minutes, seconds = clock
    else:
        raise ValueError(f"unrecognized process CPU time: {value}")
    return days * 86400 + int(hours) * 3600 + int(minutes) * 60 + float(seconds)


class ProcessSampler:
    def __init__(self, pid: int) -> None:
        self.pid = pid
        self.peak_rss_bytes = 0
        self.cpu_seconds = 0.0
        self.samples = 0
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._sample_loop, daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=2)
        self._sample()

    def _sample_loop(self) -> None:
        while not self._stop.wait(0.05):
            self._sample()

    def _sample(self) -> None:
        if os.name == "nt":
            return
        completed = subprocess.run(
            ["ps", "-o", "rss=", "-o", "time=", "-p", str(self.pid)],
            capture_output=True,
            text=True,
            check=False,
        )
        fields = completed.stdout.split()
        if completed.returncode != 0 or len(fields) < 2:
            return
        try:
            self.peak_rss_bytes = max(self.peak_rss_bytes, int(fields[0]) * 1024)
            self.cpu_seconds = max(self.cpu_seconds, parse_cpu_time(fields[1]))
            self.samples += 1
        except ValueError:
            return


def directory_size(path: Path) -> int | None:
    if not path.is_dir():
        return None
    return sum(item.stat().st_size for item in path.rglob("*") if item.is_file())


def display_path(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def stop_process(process: subprocess.Popen[object]) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=3)


def run_baseline(args: argparse.Namespace) -> dict[str, object]:
    server_path = args.server.resolve()
    if not server_path.is_file():
        raise SmokeFailure(f"server binary does not exist: {server_path}")
    if args.clients < 2:
        raise SmokeFailure("the room baseline requires at least two clients")
    if args.messages < 1 or args.warmup < 0:
        raise SmokeFailure("messages must be positive and warmup cannot be negative")

    password = "m0-performance-password"
    run_token = uuid.uuid4().hex
    base_port = find_port_range()

    with tempfile.TemporaryDirectory(prefix="chat-room-v1-performance-") as temp_path:
        temp = Path(temp_path)
        database_path = temp / "performance.db"
        log_path = temp / "server.log"
        environment = os.environ.copy()
        environment["CHATROOM_DB_PATH"] = str(database_path)
        environment["CHATROOM_DEVELOPER_KEY"] = "m0-performance-developer-key"
        environment["CHATROOM_BENCHMARK_METRICS"] = "1"

        with log_path.open("w+", encoding="utf-8") as server_log:
            process = subprocess.Popen(
                [str(server_path), "--port", str(base_port)],
                cwd=temp,
                env=environment,
                stdout=server_log,
                stderr=subprocess.STDOUT,
                text=True,
            )
            sampler = ProcessSampler(process.pid)
            sampler.start()
            clients: list[V1Client] = []
            worker_errors: list[str] = []

            try:
                wait_for_server(process, base_port)
                for index in range(args.clients):
                    client = V1Client("127.0.0.1", base_port, f"client-{index}")
                    clients.append(client)
                    username = f"m0perf_{index}_{run_token[:6]}"
                    register(client, username, f"M0 Performance {index}", password)
                    login(client, username, password)

                sender = clients[0]
                sender.send("CREATE_ROOM_REQ", {"roomName": "M0 Performance Room"})
                room_id = require_success(sender.receive_type("CREATE_ROOM_RSP")).get("roomId")
                if not isinstance(room_id, int) or room_id <= 0:
                    raise SmokeFailure(f"invalid benchmark room id: {room_id}")

                for client in clients[1:]:
                    client.send("JOIN_ROOM_REQ", {"roomId": room_id})
                    require_success(client.receive_type("JOIN_ROOM_RSP"))

                total_messages = args.warmup + args.messages
                prefix = f"m0-performance-{run_token}"

                for message_index in range(args.warmup):
                    content = f"{prefix}:{message_index}"
                    sender.send(
                        "CHAT_MSG",
                        {"roomId": room_id, "content": content, "contentType": "text"},
                    )
                    sender.receive_type(
                        "CHAT_MSG",
                        timeout=args.timeout,
                        predicate=lambda message, token=content: data(message).get("content") == token,
                    )
                    for client in clients[1:]:
                        client.receive_type(
                            "CHAT_MSG",
                            timeout=args.timeout,
                            predicate=lambda message, token=content: data(message).get("content") == token,
                        )

                def receive_fanout(client: V1Client) -> None:
                    try:
                        for message_index in range(args.warmup, total_messages):
                            expected = f"{prefix}:{message_index}"
                            client.receive_type(
                                "CHAT_MSG",
                                timeout=args.timeout,
                                predicate=lambda message, token=expected: data(message).get("content") == token,
                            )
                    except Exception as error:  # surfaced on the main thread below
                        worker_errors.append(f"{client.label}: {error}")

                workers = [
                    threading.Thread(target=receive_fanout, args=(client,), daemon=True)
                    for client in clients[1:]
                ]
                for worker in workers:
                    worker.start()

                latencies_ms: list[float] = []
                measured_start = time.perf_counter()
                measured_end = 0.0
                for message_index in range(args.warmup, total_messages):
                    content = f"{prefix}:{message_index}"
                    started = time.perf_counter()
                    sender.send(
                        "CHAT_MSG",
                        {"roomId": room_id, "content": content, "contentType": "text"},
                    )
                    sender.receive_type(
                        "CHAT_MSG",
                        timeout=args.timeout,
                        predicate=lambda message, token=content: data(message).get("content") == token,
                    )
                    latencies_ms.append((time.perf_counter() - started) * 1000)
                    measured_end = time.perf_counter()

                for worker in workers:
                    worker.join(timeout=args.timeout)
                if any(worker.is_alive() for worker in workers):
                    raise SmokeFailure("one or more fan-out receivers did not finish")
                if worker_errors:
                    raise SmokeFailure("; ".join(worker_errors))
                fanout_end = time.perf_counter()

                for client in clients:
                    client.close()
                clients.clear()
                sampler.stop()
                stop_process(process)
                server_log.flush()
                server_log.seek(0)
                server_output = server_log.read()

                sqlite_samples = [float(value) for value in SQLITE_METRIC.findall(server_output)]
                if len(sqlite_samples) != total_messages:
                    raise SmokeFailure(
                        f"expected {total_messages} SQLite metrics, observed {len(sqlite_samples)}"
                    )
                measured_sqlite = sqlite_samples[args.warmup :]

                elapsed = measured_end - measured_start
                fanout_elapsed = fanout_end - measured_start
                sqlite_bytes = sum(
                    path.stat().st_size
                    for path in (database_path, Path(f"{database_path}-wal"), Path(f"{database_path}-shm"))
                    if path.exists()
                )
                web_bytes = directory_size(args.web_dist.resolve())

                return {
                    "schemaVersion": 1,
                    "recordedAtUtc": datetime.now(timezone.utc).isoformat(),
                    "scenario": {
                        "protocol": "V1 length-prefixed JSON over loopback TCP",
                        "flow": "authenticated room CHAT_MSG persistence and fan-out",
                        "connections": args.clients,
                        "warmupMessages": args.warmup,
                        "measuredMessages": args.messages,
                        "payloadBytes": len(f"{prefix}:{total_messages - 1}".encode("utf-8")),
                        "sendPattern": "one sender, sequential send-to-own-echo acknowledgement",
                        "recipientsPerMessage": args.clients,
                    },
                    "environment": {
                        "platform": platform.platform(),
                        "machine": platform.machine(),
                        "python": platform.python_version(),
                        "qt": args.qt_version,
                        "serverBinary": display_path(server_path),
                    },
                    "results": {
                        "sendAckLatencyMs": distribution(latencies_ms),
                        "sqliteSaveLatencyUs": distribution(measured_sqlite),
                        "acceptedMessagesPerSecond": round(args.messages / elapsed, 3),
                        "fanoutDeliveriesPerSecond": round(
                            (args.messages * args.clients) / fanout_elapsed, 3
                        ),
                        "serverCpuSeconds": round(sampler.cpu_seconds, 3),
                        "serverPeakRssBytes": sampler.peak_rss_bytes,
                        "processMetricSamples": sampler.samples,
                    },
                    "artifactSizesBytes": {
                        "headlessServer": server_path.stat().st_size,
                        "sqliteWorkingSet": sqlite_bytes,
                        "webDist": web_bytes,
                        "nativeSignedInstallers": None,
                    },
                    "limitations": [
                        "Loopback removes real network latency and packet loss.",
                        "Sequential sender acknowledgements measure steady-state latency, not maximum throughput.",
                        "The headless test build disables image thumbnails and enables SQLite timing logs.",
                        "CPU time and RSS come from periodic ps sampling on Unix-like hosts.",
                        "Signed installer sizes remain an M4 measurement.",
                    ],
                }
            finally:
                for client in clients:
                    client.close()
                sampler.stop()
                stop_process(process)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--clients", type=int, default=8)
    parser.add_argument("--messages", type=int, default=100)
    parser.add_argument("--warmup", type=int, default=20)
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--qt-version", default="unknown")
    parser.add_argument("--web-dist", type=Path, default=ROOT / "WebClient" / "dist")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        result = run_baseline(args)
        output = args.output.resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(
            "[V1Performance] PASS: "
            f"p95={result['results']['sendAckLatencyMs']['p95']} ms, "
            f"SQLite p95={result['results']['sqliteSaveLatencyUs']['p95']} us, "
            f"output={output}"
        )
        return 0
    except (OSError, SmokeFailure, subprocess.SubprocessError, json.JSONDecodeError) as error:
        print(f"[V1Performance] FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
