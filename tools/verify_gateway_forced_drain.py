#!/usr/bin/env python3
"""Verify HAProxy withdrawal before a real gateway forced-drain timeout."""

import subprocess

from verify_haproxy_runtime import verify


if __name__ == "__main__":
    try:
        raise SystemExit(verify(
            "haproxyRoutesAwayBeforeForcedDrainTimeoutClosesOldSession"))
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[Gateway forced drain] verification failed: {error}")
        raise SystemExit(1)
