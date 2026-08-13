#!/usr/bin/env python3
"""Verify abrupt Java gateway loss through the shared real HAProxy harness."""

import subprocess

from verify_haproxy_runtime import verify


if __name__ == "__main__":
    try:
        raise SystemExit(verify("haproxyRemovesAbruptlyKilledGatewayAndClientRepairs"))
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[Gateway crash] verification failed: {error}")
        raise SystemExit(1)
