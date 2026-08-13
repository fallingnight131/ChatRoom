#!/usr/bin/env python3
"""Verify HAProxy master-worker reload with established gateway WSS traffic."""

import subprocess

from verify_haproxy_runtime import verify


if __name__ == "__main__":
    try:
        raise SystemExit(verify("haproxyReloadKeepsOldTunnelAndMovesNewSessions"))
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[HAProxy reload] verification failed: {error}")
        raise SystemExit(1)
