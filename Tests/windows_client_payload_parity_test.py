#!/usr/bin/env python3

import json
from pathlib import Path
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools/compare_windows_client_payloads.py"
REVISION = "a" * 40


def fixture(root: Path, client: bytes = b"client") -> None:
    files = {
        "ChatClient.exe": client,
        "ChatRoomUpdateLauncher.exe": b"launcher",
        "Qt6Core.dll": b"qt-core",
        "libsodium-26.dll": b"sodium",
        "sqldrivers/qsqlite.dll": b"sqlite",
    }
    for name, content in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)


def invoke(left: Path, right: Path, output: Path,
           extra=None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(TOOL), "--baseline", str(left), "--candidate", str(right),
         "--version", "1.2.3", "--source-revision", REVISION,
         "--output", str(output), *(extra or [])],
        text=True, capture_output=True,
    )


def main() -> int:
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        left, right = root / "qmake", root / "cmake"
        fixture(left)
        fixture(right, client=b"different-build-system-executable")
        evidence = root / "evidence/success.json"
        result = invoke(left, right, evidence)
        assert result.returncode == 0, result.stderr
        document = json.loads(evidence.read_text(encoding="utf-8"))
        assert document["runtimeBytesEquivalent"] is True
        assert document["baseline"]["Qt6Core.dll"] == document["candidate"]["Qt6Core.dll"]
        assert document["baseline"]["ChatClient.exe"] != document["candidate"]["ChatClient.exe"]

        rejected = root / "evidence/executable-drift.json"
        result = invoke(
            left, right, rejected, ["--require-executable-byte-equality"])
        assert result.returncode != 0 and not rejected.exists()

        (right / "Qt6Core.dll").write_bytes(b"drift")
        rejected = root / "evidence/runtime-drift.json"
        result = invoke(left, right, rejected)
        assert result.returncode != 0 and not rejected.exists()
        (right / "Qt6Core.dll").write_bytes(b"qt-core")

        (right / "platforms/qwindows.dll").parent.mkdir()
        (right / "platforms/qwindows.dll").write_bytes(b"extra")
        rejected = root / "evidence/extra.json"
        result = invoke(left, right, rejected)
        assert result.returncode != 0 and not rejected.exists()
        (right / "platforms/qwindows.dll").unlink()

        (right / "ChatRoomUpdateLauncher.exe").unlink()
        rejected = root / "evidence/missing.json"
        result = invoke(left, right, rejected)
        assert result.returncode != 0 and not rejected.exists()
        (right / "ChatRoomUpdateLauncher.exe").write_bytes(b"launcher")

        (right / "Qt6Core.DLL").write_bytes(b"collision")
        rejected = root / "evidence/collision.json"
        result = invoke(left, right, rejected)
        assert result.returncode != 0 and not rejected.exists()
        (right / "Qt6Core.DLL").unlink()

        link = right / "linked.dll"
        try:
            link.symlink_to(right / "Qt6Core.dll")
        except OSError:
            pass
        else:
            rejected = root / "evidence/link.json"
            result = invoke(left, right, rejected)
            assert result.returncode != 0 and not rejected.exists()

    print("Windows client payload parity policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
