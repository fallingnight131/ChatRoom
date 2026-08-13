#!/usr/bin/env python3
"""Run the Redis routing capability gate with disposable TLS and scoped ACL."""

from __future__ import annotations

import os
import shutil
import signal
import socket
import subprocess
import tempfile
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "Backend"
PASSWORD = "routing-test-password"
TRUSTSTORE_PASSWORD = "routing-test-truststore"


def required(name: str) -> str:
    value = shutil.which(name)
    if value is None:
        raise RuntimeError(f"required command not found: {name}")
    return value


def run(command: list[str], cwd: Path, environment: dict[str, str] | None = None) -> None:
    print(f"[Redis TLS] {' '.join(redacted(command))}")
    subprocess.run(command, cwd=cwd, env=environment, check=True)


def redacted(command: list[str]) -> list[str]:
    return [value.replace(PASSWORD, "<redacted>")
            .replace(TRUSTSTORE_PASSWORD, "<redacted>") for value in command]


def available_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def main() -> int:
    openssl = required("openssl")
    keytool = required("keytool")
    redis_server = required("redis-server")
    wrapper = BACKEND / ("gradlew.bat" if os.name == "nt" else "gradlew")
    port = available_port()
    with tempfile.TemporaryDirectory(prefix="chat-redis-tls-", dir="/tmp") as value:
        root = Path(value)
        ca_key, ca_cert = root / "ca.key", root / "ca.crt"
        server_key, server_request = root / "server.key", root / "server.csr"
        server_cert, extensions = root / "server.crt", root / "server.ext"
        truststore, config = root / "truststore.p12", root / "redis.conf"
        pidfile = root / "redis.pid"
        extensions.write_text(
                "subjectAltName=IP:127.0.0.1\nextendedKeyUsage=serverAuth\n",
                encoding="utf-8")
        run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes",
             "-days", "1", "-subj", "/CN=Chat Redis Test CA",
             "-keyout", str(ca_key), "-out", str(ca_cert)], ROOT)
        run([openssl, "req", "-newkey", "rsa:2048", "-nodes",
             "-subj", "/CN=127.0.0.1", "-keyout", str(server_key),
             "-out", str(server_request)], ROOT)
        run([openssl, "x509", "-req", "-days", "1", "-in", str(server_request),
             "-CA", str(ca_cert), "-CAkey", str(ca_key), "-CAcreateserial",
             "-extfile", str(extensions), "-out", str(server_cert)], ROOT)
        run([keytool, "-importcert", "-noprompt", "-alias", "chat-redis-test-ca",
             "-file", str(ca_cert), "-keystore", str(truststore),
             "-storetype", "PKCS12", "-storepass", TRUSTSTORE_PASSWORD], ROOT)
        config.write_text(
                "\n".join([
                    "bind 127.0.0.1",
                    "port 0",
                    f"tls-port {port}",
                    f"tls-cert-file {server_cert}",
                    f"tls-key-file {server_key}",
                    f"tls-ca-cert-file {ca_cert}",
                    "tls-auth-clients no",
                    "save \"\"",
                    "appendonly no",
                    f"dir {root}",
                    f"pidfile {pidfile}",
                    f"logfile {root / 'redis.log'}",
                    "daemonize yes",
                    "user default off",
                    "user chat on >" + PASSWORD
                    + " ~chat:v2:* +ping +select +set +get +del +eval +zadd +zrem"
                      " +zrangebyscore +xadd +xread +xlen",
                ]) + "\n", encoding="utf-8")
        started = False
        try:
            run([redis_server, str(config)], ROOT)
            started = True
            uri = f"rediss://chat:{PASSWORD}@127.0.0.1:{port}/0"
            environment = os.environ.copy()
            environment.update({
                "CHATROOM_TEST_REDIS_URI": uri,
                "CHATROOM_TEST_REDIS_INVALID_URI":
                    f"rediss://chat:wrong-test-password@127.0.0.1:{port}/0",
                "CHATROOM_TEST_REDIS_UNTRUSTED_URI":
                    f"rediss://chat:{PASSWORD}@localhost:{port}/0",
                "CHATROOM_TEST_REDIS_TRUST_STORE": str(truststore),
                "CHATROOM_TEST_REDIS_TRUST_STORE_PASSWORD": TRUSTSTORE_PASSWORD,
            })
            run([str(wrapper), "--no-daemon", "--no-configuration-cache",
                 ":routing-redis:test",
                 "--rerun-tasks"], BACKEND, environment)
        finally:
            if started and pidfile.is_file():
                pid = int(pidfile.read_text(encoding="utf-8").strip())
                os.kill(pid, signal.SIGTERM)
                for _ in range(100):
                    try:
                        os.kill(pid, 0)
                    except ProcessLookupError:
                        break
                    time.sleep(0.05)
                else:
                    os.kill(pid, signal.SIGKILL)
                    for _ in range(100):
                        try:
                            os.kill(pid, 0)
                        except ProcessLookupError:
                            break
                        time.sleep(0.05)
                    else:
                        raise RuntimeError("disposable Redis process did not stop")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[Redis TLS] verification failed: {error}")
        raise SystemExit(1)
