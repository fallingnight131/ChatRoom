#!/usr/bin/env python3
"""Run and validate the fixed three-by-three dual-edge reconnect ladder."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

from multi_edge_reconnect_ladder_result import (
    PROFILES,
    REPETITIONS,
    build,
    validate,
)

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.unlink(missing_ok=True)
    runs_dir = output.parent / f"{output.name}.runs"
    runs_dir.mkdir(parents=True, exist_ok=True)
    run_evidence = {profile: [] for profile in PROFILES}
    for profile in PROFILES:
        for repetition in range(1, REPETITIONS + 1):
            run_path = runs_dir / f"{profile}-run-{repetition}.json"
            log_path = runs_dir / f"{profile}-run-{repetition}.log"
            print(f"[HAProxy multi-edge ladder] starting {profile} "
                  f"run {repetition}/{REPETITIONS}", flush=True)
            try:
                with log_path.open("w", encoding="utf-8") as log:
                    subprocess.run([
                        sys.executable,
                        str(ROOT / "tools" / "verify_haproxy_multi_edge.py"),
                        "--output", str(run_path),
                        "--workload", profile,
                    ], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
            except subprocess.CalledProcessError:
                tail = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
                print("\n".join(tail[-40:]), file=sys.stderr)
                raise
            run_evidence[profile].append(
                json.loads(run_path.read_text(encoding="utf-8")))
            print(f"[HAProxy multi-edge ladder] completed {profile} "
                  f"run {repetition}/{REPETITIONS}", flush=True)
    aggregate = build(run_evidence)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(
        json.dumps(aggregate, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(output)
    validate(aggregate, aggregate["sourceRevision"])
    subprocess.run([
        sys.executable,
        str(ROOT / "tools" / "multi_edge_reconnect_ladder_result.py"),
        str(output), "--expected-revision", aggregate["sourceRevision"],
    ], cwd=ROOT, check=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"[HAProxy multi-edge ladder] verification failed: {error}")
        raise SystemExit(1)
