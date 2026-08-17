#!/usr/bin/env python3

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    workflow = (ROOT / ".github/workflows/m0-product-builds.yml").read_text(encoding="utf-8")
    ordered = (
        "Assemble qmake Windows fallback payload",
        "Compare deployed CMake and qmake Windows payloads",
        "Exercise the CMake Windows verification installer",
        "Promote verified CMake payload as canonical input",
        "Validate tested unsigned Windows installer artifact",
        "Generate Windows artifact integrity manifest",
    )
    positions = [workflow.find(marker) for marker in ordered]
    if any(position < 0 for position in positions) or positions != sorted(positions):
        raise AssertionError("Windows CMake promotion gates are missing or out of order")
    required = (
        '$baseline = "build/m4/windows-qmake-payload"',
        '$candidate = (Resolve-Path "build/m4/windows-cmake-payload").Path',
        '$canonical = "$env:GITHUB_WORKSPACE/build/m0/artifacts/windows/client"',
        "--baseline $candidate",
        "--candidate $canonical",
        "--baseline-build-system cmake",
        "--candidate-build-system cmake",
        "--require-executable-byte-equality",
        "--build-system cmake",
    )
    for marker in required:
        if marker not in workflow:
            raise AssertionError(f"canonical CMake promotion marker missing: {marker}")
    print("Windows CMake packaging promotion policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
