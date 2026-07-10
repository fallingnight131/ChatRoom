#!/usr/bin/env python3
"""Build or verify the M0 inventory extracted from authoritative V1 sources."""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BASELINE = ROOT / "docs" / "baselines" / "v1-inventory.json"

SOURCES = {
    "protocol": ROOT / "Common" / "Protocol.h",
    "server_dispatch": ROOT / "Server" / "ChatServer.cpp",
    "database_schema": ROOT / "Server" / "DatabaseManager.cpp",
}


def read_source(key: str) -> str:
    return SOURCES[key].read_text(encoding="utf-8-sig")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def first_int(pattern: str, text: str, label: str) -> int:
    match = re.search(pattern, text)
    if not match:
        raise ValueError(f"could not extract {label}")
    return int(match.group(1))


def build_inventory() -> dict[str, object]:
    protocol = read_source("protocol")
    dispatch = read_source("server_dispatch")
    database = read_source("database_schema")

    message_types = re.findall(
        r"inline\s+const\s+QString\s+([A-Z0-9_]+)\s*=", protocol
    )
    dispatched = re.findall(
        r'type\s*==\s*Protocol::MsgType::([A-Z0-9_]+)', dispatch
    )
    tables = sorted(
        set(re.findall(r"CREATE TABLE IF NOT EXISTS\s+([a-z_]+)", database))
    )
    indexes = sorted(
        set(re.findall(r"CREATE INDEX IF NOT EXISTS\s+([a-z_]+)", database))
    )
    pragmas = sorted(set(re.findall(r'q\.exec\("PRAGMA\s+([^";]+)', database)))

    if len(message_types) != len(set(message_types)):
        raise ValueError("duplicate V1 message type declarations found")

    version = first_int(r"constexpr\s+quint16\s+VERSION\s*=\s*(\d+)", protocol, "version")
    default_port = first_int(
        r"constexpr\s+quint16\s+DEFAULT_PORT\s*=\s*(\d+)", protocol, "default port"
    )
    heartbeat_interval = first_int(
        r"HEARTBEAT_INTERVAL_MS\s*=\s*(\d+)", protocol, "heartbeat interval"
    )
    heartbeat_timeout = first_int(
        r"HEARTBEAT_TIMEOUT_MS\s*=\s*(\d+)", protocol, "heartbeat timeout"
    )
    max_frame_mib = first_int(
        r"if\s*\(len\s*>\s*(\d+)\s*\*\s*1024\s*\*\s*1024\)",
        protocol,
        "TCP max frame",
    )

    return {
        "inventory_format": 1,
        "sources": {
            key: {
                "path": str(path.relative_to(ROOT)),
                "sha256": sha256(path),
            }
            for key, path in SOURCES.items()
        },
        "protocol": {
            "version_constant": version,
            "default_tcp_port": default_port,
            "default_websocket_port": default_port + 1,
            "default_http_port": default_port + 2,
            "heartbeat_interval_ms": heartbeat_interval,
            "heartbeat_timeout_ms": heartbeat_timeout,
            "tcp_max_frame_bytes": max_frame_mib * 1024 * 1024,
            "message_type_count": len(message_types),
            "message_types": message_types,
            "server_dispatched_request_count": len(dispatched),
            "server_dispatched_requests": dispatched,
        },
        "database": {
            "engine": "SQLite",
            "table_count": len(tables),
            "tables": tables,
            "explicit_index_count": len(indexes),
            "explicit_indexes": indexes,
            "connection_pragmas": pragmas,
        },
    }


def render(inventory: dict[str, object]) -> str:
    return json.dumps(inventory, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def check_baseline(path: Path, actual: str) -> int:
    if not path.exists():
        print(f"M0 baseline is missing: {path.relative_to(ROOT)}", file=sys.stderr)
        return 1
    expected = path.read_text(encoding="utf-8")
    if expected == actual:
        print(
            "M0 inventory matches "
            f"{path.relative_to(ROOT)}: "
            f"{json.loads(actual)['protocol']['message_type_count']} message types, "
            f"{json.loads(actual)['database']['table_count']} tables"
        )
        return 0
    print("M0 inventory drift detected. Regenerate and review the baseline:", file=sys.stderr)
    print(
        "".join(
            difflib.unified_diff(
                expected.splitlines(keepends=True),
                actual.splitlines(keepends=True),
                fromfile=str(path.relative_to(ROOT)),
                tofile="current-source-inventory",
            )
        ),
        file=sys.stderr,
    )
    return 1


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="compare with the saved baseline")
    mode.add_argument("--write", action="store_true", help="replace the saved baseline")
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    args = parser.parse_args()

    actual = render(build_inventory())
    baseline = args.baseline if args.baseline.is_absolute() else ROOT / args.baseline

    if args.write:
        baseline.parent.mkdir(parents=True, exist_ok=True)
        baseline.write_text(actual, encoding="utf-8")
        print(f"Wrote {baseline.relative_to(ROOT)}")
        return 0
    if args.check:
        return check_baseline(baseline, actual)
    sys.stdout.write(actual)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

