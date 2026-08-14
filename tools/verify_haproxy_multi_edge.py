#!/usr/bin/env python3
"""Verify correctness or a bounded reconnect curve across two HAProxy edges."""

import argparse
import json
import platform
import subprocess
import sys
from pathlib import Path

from multi_edge_reconnect_result import validate
from verify_haproxy_runtime import verify

ROOT = Path(__file__).resolve().parents[1]
IMAGE = "haproxy:3.2-alpine@sha256:79799e8b2977e60802774fa53d29e6b54e045402cdd8a8b9fe43923e7095a047"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--workload", choices=("step-12", "step-24", "step-48"),
        default="step-12",
        help="fixed reconnect workload profile (only used with --output)",
    )
    args = parser.parse_args()
    if args.workload != "step-12" and args.output is None:
        parser.error("non-default --workload requires --output")
    environment = {"CHATROOM_TEST_MULTI_EDGE": "true"}
    revision = None
    clean_at_start = None
    if args.output:
        revision = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, check=True,
            capture_output=True, text=True).stdout.strip()
        clean_at_start = not bool(subprocess.run(
            ["git", "status", "--porcelain"], cwd=ROOT, check=True,
            capture_output=True, text=True).stdout.strip())
        output = args.output.resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.unlink(missing_ok=True)
        environment["CHATROOM_TEST_MULTI_EDGE_RECONNECT_EVIDENCE"] = str(output)
        environment["CHATROOM_TEST_MULTI_EDGE_RECONNECT_WORKLOAD"] = args.workload
    method = ("haproxyMeasuresBatchedReconnectAfterPrimaryEdgeCrash"
              if args.output else "haproxySecondaryEdgeRepairsAfterPrimaryEdgeCrash")
    result = verify(method, environment)
    if args.output:
        evidence = json.loads(args.output.read_text(encoding="utf-8"))
        assert revision is not None and clean_at_start is not None
        evidence["sourceRevision"] = revision
        evidence["worktreeDirty"] = not clean_at_start
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
        subprocess.run([
            sys.executable, str(ROOT / "tools" / "multi_edge_reconnect_result.py"),
            str(args.output), "--expected-revision", revision], check=True)
    return result


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[HAProxy multi-edge] verification failed: {error}")
        raise SystemExit(1)
