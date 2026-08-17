#!/usr/bin/env python3

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    script = (ROOT / "tools/verify_windows_cmake_installer.ps1").read_text(encoding="utf-8")
    workflow = (ROOT / ".github/workflows/m0-product-builds.yml").read_text(encoding="utf-8")
    required_script = (
        "Get-AuthenticodeSignature",
        '"NotSigned"',
        "sqldrivers/qsqlite.dll",
        "*sodium*.dll",
        "ProductVersion.StartsWith($Version)",
        "CMake predecessor version was not registered",
        "CMake payload upgrade retained stale or transaction files",
        "CMake upgraded registration is not traceable",
        "Installed CMake client did not remain running",
        "Running-client CMake upgrade returned",
        "CMake predecessor was allowed to downgrade",
        "Rejected CMake downgrade changed installation or account data",
        "ChatRoom.UpdateLauncher.Ready.",
        "ChatRoom.UpdateLauncher.Commit.",
        'result.outcome -ne "trust-rejected"',
        "CMake payload uninstall left program files",
        "deleted account-local data",
        "PublishedInstallerPath",
        "Published CMake installer bytes differ from the verified installer",
    )
    for marker in required_script:
        if marker not in script:
            raise AssertionError(f"CMake installer behavior gate missing: {marker}")
    required_workflow = (
        "Exercise the CMake Windows verification installer",
        "verify_windows_cmake_installer.ps1",
        "-PayloadDirectory build/m4/windows-cmake-payload",
        "-Version \"${{ steps.product-version.outputs.version }}\"",
        "-SourceRevision \"${{ github.sha }}\"",
        "-PublishedInstallerPath",
    )
    for marker in required_workflow:
        if marker not in workflow:
            raise AssertionError(f"CMake installer native gate missing: {marker}")
    print("Windows CMake installer behavior gate policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
