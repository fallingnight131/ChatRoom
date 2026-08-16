#!/usr/bin/env python3

from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "cmake/WindowsV2Configuration.cmake"


def run(*definitions: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["cmake", *(f"-D{value}" for value in definitions), "-P", str(MODULE)],
        text=True, capture_output=True,
    )


def main() -> int:
    assert run().returncode == 0
    assert run(
        "CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON",
        "CHATROOM_WINDOWS_V2_WSS_URL=wss://chat.example.test/v2/windows",
    ).returncode == 0
    assert run(
        "CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON",
        "CHATROOM_ENABLE_WINDOWS_V2_FORWARDING=ON",
        "CHATROOM_ENABLE_WINDOWS_V2_SEARCH=ON",
        "CHATROOM_ENABLE_WINDOWS_V2_NOTIFICATIONS=ON",
        "CHATROOM_WINDOWS_V2_WSS_URL=wss://chat.example.test/v2/windows",
        "CHATROOM_WINDOWS_V2_FALLBACK_WSS_URL=wss://chat-secondary.example.test/v2/windows",
    ).returncode == 0

    rejected = (
        ("CHATROOM_WINDOWS_V2_WSS_URL=wss://chat.example.test/v2/windows",),
        ("CHATROOM_WINDOWS_V2_FALLBACK_WSS_URL=wss://chat-secondary.example.test/v2/windows",),
        ("CHATROOM_ENABLE_WINDOWS_V2_FORWARDING=ON",),
        ("CHATROOM_ENABLE_WINDOWS_V2_SEARCH=ON",),
        ("CHATROOM_ENABLE_WINDOWS_V2_NOTIFICATIONS=ON",),
        ("CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON",),
        ("CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON",
         "CHATROOM_WINDOWS_V2_WSS_URL=ws://chat.example.test/v2/windows"),
        ("CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON",
         "CHATROOM_WINDOWS_V2_WSS_URL=wss://user@chat.example.test/v2/windows"),
        ("CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON",
         "CHATROOM_WINDOWS_V2_WSS_URL=wss://chat.example.test/v2/web"),
        ("CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON",
         "CHATROOM_WINDOWS_V2_WSS_URL=wss://chat.example.test/v2/windows?token=x"),
        ("CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON",
         "CHATROOM_WINDOWS_V2_WSS_URL=wss://chat.example.test/v2/windows",
         "CHATROOM_WINDOWS_V2_FALLBACK_WSS_URL=ws://chat-secondary.example.test/v2/windows"),
        ("CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON",
         "CHATROOM_WINDOWS_V2_WSS_URL=wss://chat.example.test/v2/windows",
         "CHATROOM_WINDOWS_V2_FALLBACK_WSS_URL=wss://chat.example.test/v2/windows"),
    )
    for definitions in rejected:
        assert run(*definitions).returncode != 0, definitions

    print("Windows CMake V2 configuration policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
