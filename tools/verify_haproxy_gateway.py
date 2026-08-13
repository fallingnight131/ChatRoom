#!/usr/bin/env python3
"""Render and syntax-check the pinned HAProxy Java gateway edge policy."""

from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
IMAGE = "haproxy:3.2-alpine@sha256:79799e8b2977e60802774fa53d29e6b54e045402cdd8a8b9fe43923e7095a047"


def required(name: str) -> str:
    value = shutil.which(name)
    if value is None:
        raise RuntimeError(f"required command not found: {name}")
    return value


def run(command: list[str], cwd: Path) -> None:
    print(f"[HAProxy gateway] {' '.join(command)}")
    subprocess.run(command, cwd=cwd, check=True)


def main() -> int:
    python = required("python3")
    openssl = required("openssl")
    docker = required("docker")
    run([python, "-m", "unittest", "Tests/haproxy_gateway_config_test.py"], ROOT)
    with tempfile.TemporaryDirectory(prefix="chat-haproxy-", dir="/tmp") as value:
        root = Path(value)
        certificate = root / "certificate.pem"
        private_key = root / "private-key.pem"
        combined = root / "frontend.pem"
        config = root / "haproxy.cfg"
        subprocess.run([
            openssl, "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-nodes",
            "-keyout", str(private_key), "-out", str(certificate), "-days", "1",
            "-subj", "/CN=gateway.internal",
            "-addext", "subjectAltName=DNS:gateway.internal",
        ], cwd=root, check=True, capture_output=True, text=True)
        combined.write_bytes(certificate.read_bytes() + private_key.read_bytes())
        run([
            python, str(ROOT / "tools" / "render_haproxy_gateway.py"),
            "--bind-address", "0.0.0.0", "--bind-port", "8443",
            "--frontend-certificate", "/work/frontend.pem",
            "--backend-ca", "/work/certificate.pem",
            "--health-host", "chat.example.com",
            "--gateway", "gateway-a,127.0.0.1,19443,gateway.internal",
            "--gateway", "gateway-b,127.0.0.1,29443,gateway.internal",
            "--output", str(config),
        ], ROOT)
        run([
            docker, "run", "--rm", "--read-only",
            "--mount", f"type=bind,src={root},dst=/work,readonly",
            IMAGE, "haproxy", "-c", "-f", "/work/haproxy.cfg",
        ], ROOT)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[HAProxy gateway] verification failed: {error}")
        raise SystemExit(1)
