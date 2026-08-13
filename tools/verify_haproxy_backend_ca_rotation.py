#!/usr/bin/env python3
"""Verify HAProxy backend CA migration with established WSS traffic."""

import subprocess

from verify_haproxy_runtime import verify


if __name__ == "__main__":
    try:
        raise SystemExit(verify(
            "haproxyMigratesBackendCertificateAuthorityWithoutDroppingOldTunnel"))
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[HAProxy backend CA rotation] verification failed: {error}")
        raise SystemExit(1)
