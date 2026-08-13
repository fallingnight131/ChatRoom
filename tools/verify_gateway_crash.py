#!/usr/bin/env python3
"""Verify abrupt Java gateway loss through the shared real HAProxy harness."""

import argparse
import json
import platform
import subprocess
import sys
from pathlib import Path

from gateway_crash_performance_result import validate
from verify_haproxy_runtime import verify


ROOT = Path(__file__).resolve().parents[1]
IMAGE = "haproxy:3.2-alpine@sha256:79799e8b2977e60802774fa53d29e6b54e045402cdd8a8b9fe43923e7095a047"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    environment = {}
    if args.output:
        output = args.output.resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.unlink(missing_ok=True)
        environment["CHATROOM_TEST_GATEWAY_CRASH_EVIDENCE"] = str(output)
    method = ("haproxyMeasuresBatchedReconnectAfterAbruptGatewayLoss"
              if args.output else "haproxyRemovesAbruptlyKilledGatewayAndClientRepairs")
    result = verify(method, environment)
    if args.output:
        evidence = json.loads(args.output.read_text(encoding="utf-8"))
        revision = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, check=True,
            capture_output=True, text=True).stdout.strip()
        dirty = bool(subprocess.run(
            ["git", "status", "--porcelain"], cwd=ROOT, check=True,
            capture_output=True, text=True).stdout.strip())
        evidence["sourceRevision"] = revision
        evidence["worktreeDirty"] = dirty
        evidence["host"] = {
            "platform": platform.platform(),
            "pythonVersion": platform.python_version(),
            "haproxyImage": IMAGE,
        }
        temporary = args.output.with_suffix(args.output.suffix + ".tmp")
        temporary.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n",
                             encoding="utf-8")
        temporary.replace(args.output)
        validate(evidence, revision)
        subprocess.run([sys.executable,
                        str(ROOT / "tools" / "gateway_crash_performance_result.py"),
                        str(args.output), "--expected-revision", revision], check=True)
    return result


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[Gateway crash] verification failed: {error}")
        raise SystemExit(1)
