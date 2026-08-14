#!/usr/bin/env python3
"""Run the Linux /proc resident-memory integration gate in a pinned container."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
IMAGE = (
    "gradle:8.14.3-jdk21-alpine@sha256:"
    "d20561a56ff27350ea778b8151f6af913c76e9d35b6a135f927ee16e3ce8193c"
)
TEST = "*LinuxResidentMemoryIntegrationTest"
CACHE_VOLUME = "chat-room-gradle-linux"


def required(name: str) -> str:
    value = shutil.which(name)
    if value:
        return value
    raise RuntimeError(f"required command not found: {name}")


def run(command: list[str]) -> None:
    print(f"[Linux RSS] {' '.join(command)}", flush=True)
    subprocess.run(command, cwd=ROOT, check=True)


def verify() -> int:
    if not hasattr(os, "getuid") or not hasattr(os, "getgid"):
        raise RuntimeError("the pinned Linux RSS container gate requires a POSIX host")
    docker = required("docker")
    uid = os.getuid()
    gid = os.getgid()
    run([docker, "volume", "create", CACHE_VOLUME])
    run([docker, "run", "--rm", "--user", "0",
         "-v", f"{CACHE_VOLUME}:/gradle-cache", IMAGE,
         "chown", f"{uid}:{gid}", "/gradle-cache"])
    run([docker, "run", "--rm", "--user", f"{uid}:{gid}",
         "-e", "GRADLE_USER_HOME=/gradle-cache",
         "-v", f"{CACHE_VOLUME}:/gradle-cache",
         "-v", f"{ROOT}:/workspace", "-w", "/workspace/Backend",
         IMAGE, "gradle", "--no-daemon", "--no-configuration-cache",
         ":im-gateway:test", "--tests", TEST, "--rerun-tasks"])
    return 0


def main() -> int:
    return verify()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[Linux RSS] verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
