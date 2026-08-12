#!/usr/bin/env python3
"""Prove the Qt V1 client rejects an untrusted, hostname-valid TLS server."""

from __future__ import annotations

import argparse
import socket
import ssl
import subprocess
import tempfile
import threading
from pathlib import Path


def run_test(client: Path) -> None:
    if not client.is_file():
        raise RuntimeError(f"TLS policy client is missing: {client}")
    with tempfile.TemporaryDirectory(prefix="chat-room-client-tls-") as temp_name:
        temporary = Path(temp_name)
        certificate = temporary / "localhost.crt"
        private_key = temporary / "localhost.key"
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-keyout", str(private_key), "-out", str(certificate),
            "-days", "1", "-subj", "/CN=localhost",
            "-addext", "subjectAltName=DNS:localhost",
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        def exercise(mode: str) -> None:
            listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.settimeout(15)
            port = listener.getsockname()[1]
            server_result: list[str] = []

            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            context.load_cert_chain(certificate, private_key)

            def serve() -> None:
                try:
                    connection, _ = listener.accept()
                    with connection:
                        try:
                            with context.wrap_socket(connection, server_side=True):
                                server_result.append("accepted")
                        except ssl.SSLError:
                            server_result.append("client-rejected")
                except OSError as error:
                    server_result.append(f"server-error:{type(error).__name__}")
                finally:
                    listener.close()

            thread = threading.Thread(target=serve, daemon=True)
            thread.start()
            command = [str(client), "localhost", str(port), mode]
            if mode == "accept":
                command.append(str(certificate))
            completed = subprocess.run(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                timeout=15,
            )
            thread.join(timeout=15)
            if thread.is_alive():
                raise RuntimeError("TLS fixture server did not stop")
            if completed.returncode != 0:
                raise RuntimeError(f"Qt client TLS policy failed ({mode}): {completed.stdout}")
            expected = ["client-rejected"] if mode == "reject" else ["accepted"]
            if server_result != expected:
                raise RuntimeError(
                    f"TLS server observed wrong {mode} outcome: {server_result}"
                )

        exercise("reject")
        exercise("accept")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client", type=Path, required=True)
    args = parser.parse_args()
    run_test(args.client.resolve())
    print("V1 client TLS trust and encrypted-connect policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
