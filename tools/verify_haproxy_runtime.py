#!/usr/bin/env python3
"""Run two Java gateways through real HAProxy readiness withdrawal."""

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
IMAGE = "haproxy:3.2-alpine@sha256:79799e8b2977e60802774fa53d29e6b54e045402cdd8a8b9fe43923e7095a047"
TEST_USER = "chatroom_haproxy"
TEST_DATABASE = "chatroom_haproxy"


def required(name: str) -> str:
    value = shutil.which(name)
    if value:
        return value
    pg_config = shutil.which("pg_config")
    if pg_config:
        bindir = subprocess.run([pg_config, "--bindir"], check=True,
                                capture_output=True, text=True).stdout.strip()
        candidate = Path(bindir) / name
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError(f"required command not found: {name}")


def available_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def docker_reachable_host_address() -> str:
    """Return the host's routed IPv4 address without sending external traffic."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.connect(("192.0.2.1", 9))
        return str(probe.getsockname()[0])


def run(command: list[str], cwd: Path) -> None:
    print(f"[HAProxy runtime] {' '.join(command)}")
    subprocess.run(command, cwd=cwd, check=True)


def stop_redis(pidfile: Path) -> None:
    if not pidfile.is_file():
        return
    pid = int(pidfile.read_text(encoding="utf-8").strip())
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    for _ in range(200):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return
        time.sleep(0.025)
    os.kill(pid, signal.SIGKILL)


def stop_process(process: subprocess.Popen[bytes] | None) -> None:
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def generate_certificates(openssl: str, root: Path) -> None:
    ca_key = root / "ca.key"
    ca_cert = root / "ca.crt"
    run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes",
         "-days", "1", "-subj", "/CN=Chat HAProxy Test CA",
         "-keyout", str(ca_key), "-out", str(ca_cert)], ROOT)
    for name, common_name, san in (
        ("gateway", "gateway.internal", "DNS:gateway.internal"),
        ("frontend", "localhost", "DNS:localhost"),
    ):
        key = root / f"{name}.key"
        request = root / f"{name}.csr"
        certificate = root / f"{name}.crt"
        extensions = root / f"{name}.ext"
        extensions.write_text(
            f"subjectAltName={san}\nextendedKeyUsage=serverAuth\n", encoding="utf-8")
        run([openssl, "req", "-newkey", "rsa:2048", "-nodes",
             "-subj", f"/CN={common_name}", "-keyout", str(key),
             "-out", str(request)], ROOT)
        run([openssl, "x509", "-req", "-days", "1", "-in", str(request),
             "-CA", str(ca_cert), "-CAkey", str(ca_key), "-CAcreateserial",
             "-extfile", str(extensions), "-out", str(certificate)], ROOT)
    (root / "frontend.pem").write_bytes(
        (root / "frontend.crt").read_bytes() + (root / "frontend.key").read_bytes())


def await_port(port: int, process: subprocess.Popen[bytes]) -> None:
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise subprocess.CalledProcessError(process.returncode, process.args)
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                return
        except OSError:
            time.sleep(0.05)
    raise RuntimeError("HAProxy frontend did not become reachable")


def verify(test_method: str, extra_environment: dict[str, str] | None = None) -> int:
    initdb = required("initdb")
    pg_ctl = required("pg_ctl")
    createdb = required("createdb")
    redis_server = required("redis-server")
    openssl = required("openssl")
    docker = required("docker")
    python = required("python3")
    wrapper = BACKEND / ("gradlew.bat" if os.name == "nt" else "gradlew")
    proxy_port = available_port()
    postgres_port = available_port()
    redis_port = available_port()
    docker_host = docker_reachable_host_address()
    proxy_name = f"chat-haproxy-runtime-{os.getpid()}-{proxy_port}"

    with tempfile.TemporaryDirectory(prefix="chat-haproxy-runtime-", dir="/tmp") as value:
        root = Path(value)
        proxy_root = root / "haproxy"
        proxy_root.mkdir()
        data = root / "postgres-data"
        socket_dir = root / "postgres-socket"
        socket_dir.mkdir()
        postgres_log = root / "postgres.log"
        redis_pidfile = root / "redis.pid"
        redis_config = root / "redis.conf"
        redis_config.write_text("\n".join([
            "bind 127.0.0.1", f"port {redis_port}", "save \"\"",
            "appendonly no", f"dir {root}", f"pidfile {redis_pidfile}",
            f"logfile {root / 'redis.log'}", "daemonize yes",
        ]) + "\n", encoding="utf-8")
        generate_certificates(openssl, root)
        shutil.copyfile(root / "frontend.pem", proxy_root / "frontend.pem")
        shutil.copyfile(root / "ca.crt", proxy_root / "ca.crt")
        postgres_started = False
        redis_started = False
        gradle: subprocess.Popen[bytes] | None = None
        proxy: subprocess.Popen[bytes] | None = None
        run([initdb, "-D", str(data), "--username", TEST_USER, "--auth", "trust",
             "--encoding", "UTF8", "--locale", "C", "--no-sync"], ROOT)
        try:
            run([pg_ctl, "-D", str(data), "-l", str(postgres_log), "-o",
                 f"-h 127.0.0.1 -p {postgres_port} -k {socket_dir}",
                 "-w", "start"], ROOT)
            postgres_started = True
            run([createdb, "-h", "127.0.0.1", "-p", str(postgres_port),
                 "-U", TEST_USER, TEST_DATABASE], ROOT)
            run([redis_server, str(redis_config)], ROOT)
            redis_started = True
            environment = os.environ.copy()
            environment.update({
                "CHATROOM_TEST_POSTGRES_URL":
                    f"jdbc:postgresql://127.0.0.1:{postgres_port}/{TEST_DATABASE}",
                "CHATROOM_TEST_POSTGRES_USER": TEST_USER,
                "CHATROOM_TEST_POSTGRES_PASSWORD": "",
                "CHATROOM_TEST_REDIS_URI": f"redis://127.0.0.1:{redis_port}/0",
                "CHATROOM_TEST_HAPROXY_CONTROL_DIR": str(root),
                "CHATROOM_TEST_HAPROXY_WSS_URL": f"wss://localhost:{proxy_port}",
                "CHATROOM_TEST_GATEWAY_CERTIFICATE": str(root / "gateway.crt"),
                "CHATROOM_TEST_GATEWAY_PRIVATE_KEY": str(root / "gateway.key"),
            })
            if extra_environment:
                environment.update(extra_environment)
            command = [str(wrapper), "--no-daemon", "--no-configuration-cache",
                       ":im-gateway:test", "--tests",
                       "*GatewayRuntimePostgresIntegrationTest." + test_method,
                       "--rerun-tasks"]
            gradle = subprocess.Popen(command, cwd=BACKEND, env=environment)
            request = root / "haproxy-start-request"
            ports_file = root / "gateway-ports"
            started_marker = root / "haproxy-started"
            reload_request = root / "haproxy-reload-request"
            reload_marker = root / "haproxy-reloaded"
            ports: list[str] | None = None
            while gradle.poll() is None:
                if request.is_file() and ports_file.is_file() and proxy is None:
                    ports = ports_file.read_text(encoding="utf-8").splitlines()
                    if len(ports) != 2 or not all(value.isdigit() for value in ports):
                        raise RuntimeError("gateway ports control file is invalid")
                    config = proxy_root / "haproxy.cfg"
                    run([python, str(ROOT / "tools" / "render_haproxy_gateway.py"),
                         "--bind-address", "0.0.0.0", "--bind-port", "8443",
                         "--frontend-certificate", "/work/frontend.pem",
                         "--backend-ca", "/work/ca.crt",
                         "--health-host", "chat.example.com",
                         "--gateway", f"gateway-a,{docker_host},{ports[0]},gateway.internal",
                         "--gateway", f"gateway-b,{docker_host},{ports[1]},gateway.internal",
                         "--output", str(config)], ROOT)
                    proxy = subprocess.Popen([
                        docker, "run", "--rm", "--name", proxy_name, "--read-only",
                        "-p", f"127.0.0.1:{proxy_port}:8443",
                        "--mount", f"type=bind,src={proxy_root},dst=/work,readonly",
                        IMAGE, "haproxy", "-W", "-db", "-f", "/work/haproxy.cfg",
                    ], cwd=ROOT)
                    await_port(proxy_port, proxy)
                    started_marker.write_text("started\n", encoding="utf-8")
                if (proxy is not None and ports is not None
                        and reload_request.is_file() and not reload_marker.exists()):
                    retained = reload_request.read_text(encoding="utf-8").strip()
                    if retained not in ("gateway-a", "gateway-b"):
                        raise RuntimeError("HAProxy reload backend is invalid")
                    index = 0 if retained == "gateway-a" else 1
                    config = proxy_root / "haproxy.cfg"
                    run([python, str(ROOT / "tools" / "render_haproxy_gateway.py"),
                         "--bind-address", "0.0.0.0", "--bind-port", "8443",
                         "--frontend-certificate", "/work/frontend.pem",
                         "--backend-ca", "/work/ca.crt",
                         "--health-host", "chat.example.com",
                         "--gateway",
                         f"{retained},{docker_host},{ports[index]},gateway.internal",
                         "--output", str(config)], ROOT)
                    run([docker, "kill", "--signal", "USR2", proxy_name], ROOT)
                    time.sleep(2)
                    if proxy.poll() is not None:
                        raise subprocess.CalledProcessError(proxy.returncode, proxy.args)
                    reload_marker.write_text("reloaded\n", encoding="utf-8")
                time.sleep(0.02)
            if gradle.returncode != 0:
                raise subprocess.CalledProcessError(gradle.returncode, command)
        finally:
            stop_process(gradle)
            stop_process(proxy)
            subprocess.run([docker, "rm", "-f", proxy_name], cwd=ROOT,
                           check=False, capture_output=True)
            if redis_started:
                stop_redis(redis_pidfile)
            if postgres_started:
                run([pg_ctl, "-D", str(data), "-m", "fast", "-w", "stop"], ROOT)
    return 0


def main() -> int:
    return verify("haproxyWithdrawsOneGatewayWhileItsExistingWssSessionDrains")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[HAProxy runtime] verification failed: {error}")
        raise SystemExit(1)
