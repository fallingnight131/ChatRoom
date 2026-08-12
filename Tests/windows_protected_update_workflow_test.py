#!/usr/bin/env python3

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/m4-windows-protected-update-signing.yml"
SIGNER = ROOT / "tools/sign_windows_update_manifest_protected.ps1"


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
            current = lines[index]
            if current.strip() and len(current) - len(current.lstrip()) <= indentation:
                break
            block.append(current)
            index += 1
        blocks.append("\n".join(block))
    return "\n".join(blocks)


def main() -> int:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    if not SIGNER.is_file():
        raise AssertionError("protected update signer is missing")
    required = (
        "workflow_dispatch:",
        "environment: windows-update-production-signing",
        "runs-on: [self-hosted, windows, x64, self-hosted-windows-update-signing]",
        "actions: read",
        "contents: read",
        "persist-credentials: false",
        "actions/download-artifact@v8",
        "windows_release_candidate.py verify",
        "windows_update_manifest.py create",
        "sign_windows_update_manifest_protected.ps1",
        "windows_update_manifest.py verify",
        "windows_update_channel_candidate.py assemble",
        "windows_update_channel_candidate.py verify",
        "CHATROOM_UPDATE_PUBLIC_KEY_PATH",
        "signed-update-not-published",
        "retention-days: 7",
    )
    for marker in required:
        if marker not in workflow:
            raise AssertionError(f"protected update workflow marker missing: {marker}")

    ordered = (
        "Validate immutable update-signing inputs",
        "Download the exact signed Windows candidate",
        "Independently verify signed Windows candidate",
        "Author the short-lived canonical update manifest",
        "Sign through the protected non-exportable Ed25519 key",
        "Assemble and independently verify unpublished update candidate",
        "Upload unpublished signed update candidate evidence",
    )
    positions = [workflow.find(marker) for marker in ordered]
    if any(position < 0 for position in positions) or positions != sorted(positions):
        raise AssertionError("protected update workflow order is unsafe")

    shell = run_blocks(workflow)
    if "${{ inputs." in shell:
        raise AssertionError("workflow input was interpolated directly into a shell block")
    forbidden = (
        "secrets.", "private_key", "private-key", "pfx", "certificate_password",
        "pin-source", "pin-value", "password", "Import-PfxCertificate",
        "ConvertTo-SecureString", "choco install", "winget install",
        "windows_update_manifest.py sign", "gh release", "create-release",
        "upload-release-asset", "git push", "Invoke-WebRequest",
    )
    lowered = workflow.lower()
    for marker in forbidden:
        if marker.lower() in lowered:
            raise AssertionError(f"protected update workflow contains forbidden logic: {marker}")
    if workflow.count("uses: actions/upload-artifact@") != 1:
        raise AssertionError("protected update workflow must upload exactly one candidate")
    if workflow.count("uses: actions/download-artifact@") != 1:
        raise AssertionError("protected update workflow must consume exactly one candidate")
    print("Windows protected update workflow policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
