#!/usr/bin/env python3

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = (ROOT / "tools/verify_windows_signed_install.ps1").read_text(encoding="utf-8")


def main() -> int:
    required = (
        "Start-Process -FilePath $installer.FullName",
        '@("/S", "/D=$root")',
        'Resolve-RegularFile "$root/ChatClient.exe"',
        'Resolve-RegularFile "$root/ChatRoomUpdateLauncher.exe"',
        'Resolve-RegularFile "$root/Uninstall.exe"',
        "Get-AuthenticodeSignature",
        "TimeStamperCertificate",
        "ExpectedSignerSha256",
        "Installed $($pair.Role) bytes do not match the signed source",
        "DisplayVersion -cne $Version",
        "SourceRevision -cne $SourceRevision",
        "Start-Process -FilePath $installedUninstaller.FullName",
        "install-uninstall-observed",
        "installRootRemoved = $true",
        "temporaryPathsRemoved = $true",
        "registrationRemoved = $true",
        "$root.__chatroom_stage",
        "$root.__chatroom_backup",
        "already exists",
        "FileAttributes]::ReparsePoint",
    )
    for marker in required:
        if marker not in SCRIPT:
            raise AssertionError(f"Windows signed-install policy is missing: {marker}")
    forbidden = ("Remove-Item $root", "Remove-Item -Recurse", "Remove-Item -Path $root")
    for marker in forbidden:
        if marker in SCRIPT:
            raise AssertionError("Windows signed-install verifier must not erase failed evidence")
    print("Windows signed install/uninstall policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
