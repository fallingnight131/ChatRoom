#!/usr/bin/env python3

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    source = (ROOT / "Client/main.cpp").read_text(encoding="utf-8")
    diagnostic = (ROOT / "Client/WindowsUpdateTrustDiagnostic.cpp").read_text(
        encoding="utf-8")
    cmake = (ROOT / "CMakeLists.txt").read_text(encoding="utf-8")
    qmake = (ROOT / "Client/Client.pro").read_text(encoding="utf-8")
    runner = (ROOT / "tools/invoke_windows_json_diagnostic.ps1").read_text(
        encoding="utf-8")
    argument = "--chatroom-print-update-trust-json"
    required = (
        "WindowsUpdateProductConfiguration::fromBuild()",
        "WindowsUpdateTrustDiagnostic::canonicalJson",
        "GetStdHandle(STD_OUTPUT_HANDLE)",
        "WriteFile",
        "writeWindowsDiagnosticOutput(output)",
        argument,
    )
    for marker in required:
        if marker not in source:
            raise AssertionError(f"Windows trust diagnostic marker missing: {marker}")
    positions = [
        source.find(argument), source.find("QApplication app(argc, argv)"),
        source.find("WindowsClientInstanceGuard instanceGuard"),
        source.find("WindowsUpdateController updateController"),
    ]
    if any(position < 0 for position in positions) or positions != sorted(positions):
        raise AssertionError("Windows trust diagnostic is not before UI/network/update startup")
    for marker in (
        'QStringLiteral("enabled")', 'QStringLiteral("channel")',
        'QStringLiteral("manifestUrl")', 'QStringLiteral("signatureUrl")',
        'QStringLiteral("trustedKeys")', 'QStringLiteral("keyId")',
        'QStringLiteral("publicKeyHex")', "QJsonDocument::Compact",
    ):
        if marker not in diagnostic:
            raise AssertionError(f"Windows trust diagnostic schema missing: {marker}")
    lowered = diagnostic.lower()
    for marker in ("privatekey", "password", "secret", "token", "credential"):
        if marker in lowered:
            raise AssertionError(f"Windows trust diagnostic exposes forbidden field: {marker}")
    for graph, name in ((cmake, "CMake"), (qmake, "qmake")):
        for marker in (
            "WindowsUpdateTrustDiagnostic.cpp", "WindowsUpdateTrustDiagnostic.h"):
            if marker not in graph:
                raise AssertionError(f"{name} omits Windows trust diagnostic: {marker}")
    for marker in (
        "Start-Process", "-RedirectStandardOutput", "-Wait", "-PassThru",
        "Windows JSON diagnostic returned empty output",
    ):
        if marker not in runner:
            raise AssertionError(f"Windows diagnostic runner missing: {marker}")
    for workflow_path in (
        ".github/workflows/m0-product-builds.yml",
        ".github/workflows/m4-windows-product-trust-build.yml",
        ".github/workflows/m4-windows-protected-signing.yml",
    ):
        workflow = (ROOT / workflow_path).read_text(encoding="utf-8")
        if "invoke_windows_json_diagnostic.ps1" not in workflow \
                or "Invoke-ChatRoomWindowsJsonDiagnostic" not in workflow:
            raise AssertionError(
                f"Windows diagnostic workflow omits redirected runner: {workflow_path}")
    print("Windows update trust diagnostic policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
