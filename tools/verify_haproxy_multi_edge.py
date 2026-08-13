#!/usr/bin/env python3
"""Verify client recovery after one of two independent HAProxy edges fails."""

import subprocess

from verify_haproxy_runtime import verify


if __name__ == "__main__":
    try:
        raise SystemExit(verify(
            "haproxySecondaryEdgeRepairsAfterPrimaryEdgeCrash",
            {"CHATROOM_TEST_MULTI_EDGE": "true"}))
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[HAProxy multi-edge] verification failed: {error}")
        raise SystemExit(1)
