#!/usr/bin/env python3
"""Verify HAProxy frontend certificate rotation with established WSS traffic."""

import subprocess

from verify_haproxy_runtime import verify


if __name__ == "__main__":
    try:
        raise SystemExit(verify(
            "haproxyRotatesFrontendCertificateWithoutDroppingOldTunnel"))
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[HAProxy certificate rotation] verification failed: {error}")
        raise SystemExit(1)
