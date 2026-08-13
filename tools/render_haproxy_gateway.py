#!/usr/bin/env python3
"""Render the reviewed HAProxy edge configuration for Java WSS gateways."""

from __future__ import annotations

import argparse
import ipaddress
import re
from dataclasses import dataclass
from pathlib import Path, PurePosixPath


NAME = re.compile(r"[a-z][a-z0-9-]{0,31}")
HOST = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")


@dataclass(frozen=True)
class Gateway:
    name: str
    host: str
    product_port: int
    verify_host: str


def port(value: str, name: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise ValueError(f"{name} must be an integer") from error
    if parsed < 1 or parsed > 65_535:
        raise ValueError(f"{name} must be in 1..65535")
    return parsed


def safe_host(value: str, name: str) -> str:
    if not HOST.fullmatch(value) or ".." in value:
        raise ValueError(f"{name} must be a DNS name or IPv4 address")
    return value


def absolute_path(value: str, name: str) -> str:
    path = PurePosixPath(value)
    if not path.is_absolute() or any(character.isspace() for character in value):
        raise ValueError(f"{name} must be an absolute POSIX path without whitespace")
    return str(path)


def gateway(value: str) -> Gateway:
    fields = value.split(",")
    if len(fields) != 4:
        raise ValueError(
            "gateway must be name,host,productPort,verifyHost")
    name, host, product, verify_host = fields
    if not NAME.fullmatch(name):
        raise ValueError("gateway name must match [a-z][a-z0-9-]{0,31}")
    return Gateway(name, safe_host(host, "gateway host"),
                   port(product, "product port"),
                   safe_host(verify_host, "verify host"))


def render(bind_address: str, bind_port: int, frontend_certificate: str,
           backend_ca: str, health_host: str, gateways: list[Gateway]) -> str:
    try:
        ipaddress.ip_address(bind_address)
    except ValueError as error:
        raise ValueError("bind address must be an IP address") from error
    if not gateways:
        raise ValueError("at least one gateway is required")
    if len(gateways) > 64:
        raise ValueError("at most 64 gateways are supported")
    names = [value.name for value in gateways]
    if len(names) != len(set(names)):
        raise ValueError("gateway names must be unique")
    frontend_certificate = absolute_path(frontend_certificate, "frontend certificate")
    backend_ca = absolute_path(backend_ca, "backend CA")
    health_host = safe_host(health_host, "health host")

    lines = [
        "global",
        "    log stdout format raw local0",
        "    master-worker",
        "",
        "defaults",
        "    log global",
        "    mode http",
        "    option httplog",
        "    option dontlognull",
        "    timeout connect 5s",
        "    timeout http-request 10s",
        "    timeout queue 5s",
        "    timeout client 5m",
        "    timeout server 5m",
        "    timeout tunnel 5m",
        "",
        "frontend chat_wss",
        f"    bind {bind_address}:{bind_port} ssl crt {frontend_certificate} alpn http/1.1",
        "    http-request del-header Forwarded",
        "    http-request del-header X-Forwarded-For",
        "    http-request del-header X-Forwarded-Proto",
        "    http-request set-header X-Forwarded-For %[src]",
        "    http-request set-header X-Forwarded-Proto https",
        "    default_backend chat_gateways",
        "",
        "backend chat_gateways",
        "    balance leastconn",
        "    option httpchk",
        f"    http-check send meth GET uri /health/ready ver HTTP/1.1 hdr Host {health_host}",
        "    http-check expect status 200",
    ]
    for value in gateways:
        lines.append(
            f"    server {value.name} {value.host}:{value.product_port} ssl "
            f"verify required ca-file {backend_ca} verifyhost {value.verify_host} "
            "check inter 1s fastinter 250ms "
            "downinter 1s fall 2 rise 2")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind-address", default="0.0.0.0")
    parser.add_argument("--bind-port", default="443")
    parser.add_argument("--frontend-certificate", required=True)
    parser.add_argument("--backend-ca", required=True)
    parser.add_argument("--health-host", required=True)
    parser.add_argument("--gateway", action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        gateways = [gateway(value) for value in args.gateway]
        content = render(args.bind_address, port(args.bind_port, "bind port"),
                         args.frontend_certificate, args.backend_ca,
                         args.health_host, gateways)
    except ValueError as error:
        parser.error(str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(content, encoding="utf-8")
    temporary.replace(args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
