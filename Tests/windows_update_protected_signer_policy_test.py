#!/usr/bin/env python3

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = (ROOT / "tools/sign_windows_update_manifest_protected.ps1").read_text(
    encoding="utf-8")


def main() -> int:
    required = (
        "CHATROOM_UPDATE_SIGNING_KEY_URI",
        "^pkcs11:",
        "pin-source|pin-value",
        "ExpectedPublicKeyFileSha256",
        "ExpectedKeyId",
        "windows_update_manifest.py inspect",
        "Get-FileHash",
        "OpenSSL 3",
        "pkeyutl -sign -rawin -inkey $keyUri",
        "pkeyutl -verify -pubin -rawin -inkey $publicKey.FullName",
        "Length -ne 64",
        "[IO.File]::Move($temporaryFile.FullName, $signature.FullName)",
        "already exists",
        "FileAttributes]::ReparsePoint",
    )
    for marker in required:
        if marker not in SCRIPT:
            raise AssertionError(f"protected update signer policy is missing: {marker}")
    forbidden = (
        "privatekeypath", "private_key", "private-key", "pfx",
        "convertto-securestring", "import-pfxcertificate",
        "choco install", "winget install", "invoke-webrequest", "secrets.",
    )
    lowered = SCRIPT.lower()
    for marker in forbidden:
        if marker in lowered:
            raise AssertionError(f"protected update signer contains forbidden input: {marker}")
    if re.search(r"\[string\]\$[^\r\n]*(password|pin|secret)", SCRIPT, re.I):
        raise AssertionError("protected update signer exposes a credential parameter")
    if SCRIPT.count("[IO.File]::Move($temporaryFile.FullName, $signature.FullName)") != 1:
        raise AssertionError("protected update signature publication is ambiguous")
    print("Windows protected update signer policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
