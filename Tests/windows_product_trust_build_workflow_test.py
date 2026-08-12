#!/usr/bin/env python3

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/m4-windows-product-trust-build.yml"


def run_blocks(source: str) -> str:
    blocks = []
    lines = source.splitlines()
    index = 0
    while index < len(lines):
        match = re.match(r"^(\s*)run:\s*\|\s*$", lines[index])
        if not match:
            index += 1
            continue
        indentation = len(match.group(1))
        index += 1
        block = []
        while index < len(lines):
            line = lines[index]
            if line.strip() and len(line) - len(line.lstrip()) <= indentation:
                break
            block.append(line)
            index += 1
        blocks.append("\n".join(block))
    return "\n".join(blocks)


def main() -> int:
    source = WORKFLOW.read_text(encoding="utf-8")
    required = (
        "environment: windows-update-product-trust",
        "runs-on: [self-hosted, windows, x64, self-hosted-windows-update-trust-build]",
        "actions: read", "contents: read", "persist-credentials: false",
        "windows_update_product_trust_intent.py", "--forbid-product-update-trust",
        "-DCHATROOM_BUILD_HEADLESS_SERVER=ON",
        "-DCHATROOM_ENABLE_WINDOWS_UPDATES=ON",
        "--chatroom-print-update-trust-json",
        "windows_update_product_trust_evidence.py",
        "--baseline-build-system cmake-default-off",
        "product-trust payload must remain unsigned",
        "installed trust differs from built PE",
        "windows_artifact_manifest.py", "--require-product-update-trust",
        "unsigned-product-trust", "retention-days: 7",
    )
    for marker in required:
        if marker not in source:
            raise AssertionError(f"product trust workflow marker missing: {marker}")
    ordered = (
        "Validate public trust build inputs",
        "Create and verify reviewed product trust intent",
        "Download exact ordinary null-trust artifact",
        "Verify ordinary null-trust baseline",
        "Build exact trust-enabled CMake client",
        "Attest final binary trust and runtime parity",
        "Package and exercise unsigned trust-enabled installer",
        "Assemble and independently verify schema-four trust artifact",
        "Upload unsigned product-trust candidate",
    )
    positions = [source.find(marker) for marker in ordered]
    if any(position < 0 for position in positions) or positions != sorted(positions):
        raise AssertionError("product trust workflow order is unsafe")
    if "${{ inputs." in run_blocks(source):
        raise AssertionError("workflow input is interpolated directly into shell")
    lowered = source.lower()
    for marker in (
        "secrets.", "private_key", "private-key", "pfx", "password", "pin-value",
        "signtool", "windows_update_manifest.py sign", "gh release", "git push",
        "upload-release-asset", "invoke-webrequest", "choco install", "vcpkg install",
    ):
        if marker in lowered:
            raise AssertionError(f"product trust workflow contains forbidden logic: {marker}")
    if source.count("uses: actions/download-artifact@") != 1:
        raise AssertionError("product trust workflow must download one baseline artifact")
    if source.count("uses: actions/upload-artifact@") != 1:
        raise AssertionError("product trust workflow must upload one candidate artifact")
    print("Windows product trust build workflow policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
