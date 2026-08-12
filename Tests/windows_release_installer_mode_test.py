#!/usr/bin/env python3

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "packaging/windows/ChatRoom.nsi"


def main() -> int:
    source = SCRIPT.read_text(encoding="utf-8")
    release = 'OutFile "${OUTPUT_DIR}\\ChatRoom-${VERSION}-Setup.exe"'
    verification = (
        'OutFile "${OUTPUT_DIR}\\ChatRoom-${VERSION}-unsigned-verification-Setup.exe"')
    if source.count("!ifdef RELEASE_BUILD") != 1 or source.count("!else") < 1:
        raise AssertionError("NSIS release output mode is not explicit")
    if source.count(release) != 1 or source.count(verification) != 1:
        raise AssertionError("NSIS output identities are ambiguous")
    block = re.search(r"!ifdef RELEASE_BUILD(?P<release>.*?)!else(?P<verification>.*?)!endif",
                      source, re.S)
    if not block or release not in block.group("release") or verification not in block.group("verification"):
        raise AssertionError("NSIS release and verification names are in the wrong branches")
    if re.search(r"(?im)^\s*!finalize\b|^\s*!uninstfinalize\b", source):
        raise AssertionError("NSIS must not invoke signing commands")
    print("Windows release installer output mode policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
