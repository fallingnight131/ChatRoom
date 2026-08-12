#!/usr/bin/env python3

from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "cmake/WindowsUpdateConfiguration.cmake"
KEY = "a" * 64


def run(*definitions: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["cmake", *(f"-D{value}" for value in definitions), "-P", str(MODULE)],
        text=True, capture_output=True,
    )


def enabled(*extra: str) -> tuple[str, ...]:
    return (
        "CHATROOM_ENABLE_WINDOWS_UPDATES=ON",
        "CHATROOM_UPDATE_CHANNEL=stable",
        "CHATROOM_UPDATE_MANIFEST_URL=https://updates.example.test/windows/stable/manifest.json",
        "CHATROOM_UPDATE_PRIMARY_KEY_ID=windows-update-2026-01",
        f"CHATROOM_UPDATE_PRIMARY_PUBLIC_KEY_HEX={KEY}",
        *extra,
    )


def main() -> int:
    assert run().returncode == 0
    assert run(*enabled()).returncode == 0
    assert run(*enabled(
        "CHATROOM_UPDATE_SECONDARY_KEY_ID=windows-update-2027-01",
        f"CHATROOM_UPDATE_SECONDARY_PUBLIC_KEY_HEX={'b' * 64}",
    )).returncode == 0

    rejected = (
        ("CHATROOM_UPDATE_CHANNEL=stable",),
        ("CHATROOM_ENABLE_WINDOWS_UPDATES=ON",),
        (*enabled("CHATROOM_UPDATE_MANIFEST_URL=http://updates.example.test/manifest.json"),),
        (*enabled("CHATROOM_UPDATE_MANIFEST_URL=https://updates.example.test/manifest.json?token=x"),),
        (*enabled("CHATROOM_UPDATE_MANIFEST_URL=https://updates.example.test/windows/beta/manifest.json"),),
        (*enabled("CHATROOM_UPDATE_MANIFEST_URL=https://updates.example.test/windows//stable/manifest.json"),),
        (*enabled("CHATROOM_UPDATE_PRIMARY_KEY_ID=UPPER"),),
        (*enabled(f"CHATROOM_UPDATE_PRIMARY_PUBLIC_KEY_HEX={'A' * 64}"),),
        (*enabled("CHATROOM_UPDATE_SECONDARY_KEY_ID=windows-update-2027-01"),),
    )
    for definitions in rejected:
        result = run(*definitions)
        assert result.returncode != 0, definitions

    print("Windows CMake update configuration policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
