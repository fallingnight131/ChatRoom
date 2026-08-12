#!/usr/bin/env python3

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/m4-windows-protected-signing.yml"
SIGNER = ROOT / "tools/sign_windows_release_subjects.ps1"


def run_blocks(source: str) -> str:
    blocks = []
    lines = source.splitlines()
    index = 0
    while index < len(lines):
        line = lines[index]
        match = re.match(r"^(\s*)run:\s*\|\s*$", line)
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
    signer = SIGNER.read_text(encoding="utf-8")
    required_workflow = (
        "workflow_dispatch:",
        "environment: windows-production-signing",
        "runs-on: [self-hosted, windows, x64, self-hosted-windows-signing]",
        "actions: read",
        "contents: read",
        "persist-credentials: false",
        "windows_protected_release_intent.py create",
        "windows_protected_release_intent.py verify",
        "actions/download-artifact@",
        "verify_windows_unsigned_artifact.py",
        'Status -ne "NotSigned"',
        "sign_windows_release_subjects.ps1",
        "/DEXPORT_UNINSTALLER=1",
        "-Mode Uninstaller",
        "/DIMPORT_SIGNED_UNINSTALLER=1",
        "/DRELEASE_BUILD=1",
        "verify_windows_release_signatures.ps1",
        "windows_release_evidence.py",
        "windows_release_candidate.py assemble",
        "--protected-signing-intent",
        "--uninstaller",
        "windows_release_candidate.py verify",
        "signed-not-published",
        "retention-days: 7",
        'if ($nsisVersion -cne "v3.12")',
        "Remove-Item build/m4/protected-signing -Recurse -Force",
    )
    for marker in required_workflow:
        if marker not in workflow:
            raise AssertionError(f"protected signing workflow marker missing: {marker}")

    ordered = (
        "Validate immutable signing inputs",
        "Create the approved protected-signing intent",
        "Download the exact unsigned Windows artifact",
        "Independently verify unsigned signing intake",
        "Sign payload subjects from the machine certificate store",
        "Export, sign, import, and sign canonical release installer",
        "Generate and independently verify signature evidence",
        "Assemble and independently verify unpublished candidate",
        "Upload unpublished signed candidate evidence",
    )
    positions = [workflow.find(marker) for marker in ordered]
    if any(position < 0 for position in positions) or positions != sorted(positions):
        raise AssertionError("protected signing workflow order is unsafe")

    shell = run_blocks(workflow)
    if "${{ inputs." in shell:
        raise AssertionError("workflow input was interpolated directly into a shell block")
    forbidden = (
        "secrets.", "Import-PfxCertificate", "ConvertTo-SecureString",
        "certificate_password", "pfx_password", "private_key",
        "choco install", "vcpkg install", "install-qt-action",
        "gh release", "action-gh-release", "create-release", "upload-release-asset",
        "windows_update_manifest.py sign",
    )
    combined = (workflow + "\n" + signer).lower()
    for marker in forbidden:
        if marker.lower() in combined:
            raise AssertionError(f"protected signing boundary contains forbidden logic: {marker}")

    required_signer = (
        "Get-AuthenticodeSignature",
        "SignatureStatus]::NotSigned",
        "Cert:\\LocalMachine\\My",
        "HasPrivateKey",
        "1.3.6.1.5.5.7.3.3",
        "HashAlgorithmName]::SHA256",
        "ExpectedCertificateSha256",
        "Get-Command signtool.exe",
        "/sha1 $CertificateSha1 /sm /s My /fd SHA256",
        "/tr $TimestampUrl /td SHA256",
        'ValidateSet("Payload", "Uninstaller", "Installer")',
        "ChatRoom-(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)-Uninstall\\.exe",
    )
    for marker in required_signer:
        if marker not in signer:
            raise AssertionError(f"machine-store signing policy missing: {marker}")

    if workflow.count("uses: actions/upload-artifact@") != 1:
        raise AssertionError("protected workflow must upload exactly one candidate artifact")
    print("Windows protected signing workflow policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
