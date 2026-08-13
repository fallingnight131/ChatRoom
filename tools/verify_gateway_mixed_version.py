#!/usr/bin/env python3
"""Build two committed gateway revisions and verify mixed-version WSS delivery."""

from __future__ import annotations

import argparse
import os
import subprocess
import tarfile
import tempfile
from pathlib import Path

from verify_haproxy_runtime import ROOT, verify


DEFAULT_PREVIOUS = "1487e1f08992a1b4d10a3d5ece59b4fa8c935ac5"


def revision(value: str) -> str:
    return subprocess.run(
        ["git", "rev-parse", "--verify", value + "^{commit}"], cwd=ROOT,
        check=True, capture_output=True, text=True).stdout.strip()


def build_distribution(source: Path) -> str:
    backend = source / "Backend"
    wrapper = backend / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run([str(wrapper), "--no-daemon", ":im-gateway:installDist"],
                   cwd=backend, check=True)
    libraries = sorted((backend / "im-gateway" / "build" / "install"
                        / "im-gateway" / "lib").glob("*.jar"))
    if not libraries:
        raise RuntimeError("gateway distribution contains no runtime libraries")
    return os.pathsep.join(str(path) for path in libraries)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous-revision", default=DEFAULT_PREVIOUS)
    args = parser.parse_args()
    dirty = subprocess.run(
        ["git", "status", "--porcelain"], cwd=ROOT, check=True,
        capture_output=True, text=True).stdout.strip()
    if dirty:
        raise RuntimeError("mixed-version gate requires a clean candidate worktree")
    previous = revision(args.previous_revision)
    candidate = revision("HEAD")
    if previous == candidate:
        raise RuntimeError("mixed-version revisions must differ")

    with tempfile.TemporaryDirectory(prefix="chat-gateway-previous-", dir="/tmp") as value:
        previous_root = Path(value) / "source"
        previous_root.mkdir()
        archive = Path(value) / "source.tar"
        subprocess.run(
            ["git", "archive", "--format=tar", f"--output={archive}", previous],
            cwd=ROOT, check=True)
        with tarfile.open(archive, "r") as source:
            # The archive is produced from this repository's trusted Git object.
            source.extractall(previous_root)
        previous_classpath = build_distribution(previous_root)
        candidate_classpath = build_distribution(ROOT)
        return verify("haproxyRollsAcrossTwoCommittedGatewayVersions", {
            "CHATROOM_TEST_GATEWAY_PREVIOUS_CLASSPATH": previous_classpath,
            "CHATROOM_TEST_GATEWAY_CANDIDATE_CLASSPATH": candidate_classpath,
            "CHATROOM_TEST_GATEWAY_PREVIOUS_REVISION": previous,
            "CHATROOM_TEST_GATEWAY_CANDIDATE_REVISION": candidate,
        })


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError, tarfile.TarError) as error:
        print(f"[Gateway mixed version] verification failed: {error}")
        raise SystemExit(1)
