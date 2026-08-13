#!/usr/bin/env python3
"""Keep the Windows CMake product graph aligned with the qmake fallback."""

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]


def qmake_sources(project: Path, prefix: str) -> set[str]:
    text = project.read_text(encoding="utf-8")
    match = re.search(r"SOURCES\s*\+=\s*\\\n(?P<body>.*?)(?:\n\s*\n|\nHEADERS)", text, re.S)
    if not match:
        raise AssertionError(f"SOURCES block missing from {project}")
    sources = set()
    for raw in match.group("body").splitlines():
        value = raw.strip().removesuffix("\\").strip()
        if value:
            sources.add(str(Path(prefix, value)))
    return sources


def main() -> int:
    cmake = (ROOT / "CMakeLists.txt").read_text(encoding="utf-8")
    workflow = (ROOT / ".github/workflows/m0-product-builds.yml").read_text(
        encoding="utf-8"
    )
    expected = qmake_sources(ROOT / "Client/Client.pro", "Client")
    expected |= qmake_sources(ROOT / "UpdaterLauncher/UpdaterLauncher.pro", "UpdaterLauncher")
    expected = {str((ROOT / value).resolve().relative_to(ROOT)) for value in expected}
    missing = sorted(value for value in expected if value not in cmake)
    if missing:
        raise AssertionError(f"CMake product graph omits qmake sources: {missing}")

    required_cmake = (
        "CHATROOM_BUILD_WINDOWS_CLIENT",
        'message(FATAL_ERROR "CHATROOM_BUILD_WINDOWS_CLIENT requires a Windows host")',
        '"CHATROOM_BUILD_WINDOWS_CLIENT currently requires the shared Qt V1 graph"',
        "add_executable(\n                ChatClient WIN32",
        "add_executable(\n                ChatRoomUpdateLauncher WIN32",
        "Client/resources/windows_product.rc.in",
        "chatroom_windows_v2_transport",
        "cmake/ChatRoomV2Protobuf.cmake",
        "protobuf::libprotobuf",
        "Qt6::WebSockets",
        'CHAT_APP_VERSION="${CHATROOM_PRODUCT_VERSION}"',
        "CHATROOM_ENABLE_WINDOWS_UPDATES",
        "CHATROOM_ENABLE_WINDOWS_V2_PREVIEW",
        "CHATROOM_WINDOWS_V2_WSS_URL",
        "CHATROOM_WINDOWS_V2_FALLBACK_WSS_URL",
        "chatroom_windows_v2_configuration",
        "CHAT_WINDOWS_V2_PRODUCT_AVAILABLE=1",
        "WindowsDeviceManagementController.cpp",
        'CHAT_UPDATE_CONFIGURATION_ENABLED=1',
        'CHAT_UPDATE_MANIFEST_URL="${CHATROOM_UPDATE_MANIFEST_URL}"',
    )
    for marker in required_cmake:
        if marker not in cmake:
            raise AssertionError(f"Windows CMake policy marker missing: {marker}")

    required_workflow = (
        "Build Windows CMake client targets in Release mode",
        "-DCHATROOM_BUILD_WINDOWS_CLIENT=ON",
        "--target ChatClient ChatRoomUpdateLauncher",
        "WindowsV2ProductConfigurationEnabledTest",
        "V2WindowsDeviceManagementTransportTest",
        "Enabled Windows V2 configuration test failed",
        "CMake client version does not match canonical VERSION",
        "CMake update launcher version does not match canonical VERSION",
        "Compare deployed CMake and qmake Windows payloads",
        "compare_windows_client_payloads.py",
        "windows_client_payload_parity_test.py",
    )
    for marker in required_workflow:
        if marker not in workflow:
            raise AssertionError(f"native Windows CMake gate missing: {marker}")

    protobuf_policy = (ROOT / "cmake/ChatRoomV2Protobuf.cmake").read_text(
        encoding="utf-8"
    )
    required_protobuf = (
        "protobuf-35.1.tar.gz",
        "SHA256=f0b6838e7522a8da96126d487068c959bc624926368f3024ac8fd03abd0a1ac4",
        "abseil-cpp-20250512.1.tar.gz",
        "SHA256=9b7a064305e9fd94d124ffa6cc358592eb42b5da588fb4e07d09254aa40086db",
        'set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)',
    )
    for marker in required_protobuf:
        if marker not in protobuf_policy:
            raise AssertionError(f"Windows static Protobuf policy marker missing: {marker}")
    if "protobuf" in (ROOT / "vcpkg.json").read_text(encoding="utf-8"):
        raise AssertionError("Windows V2 Protobuf must remain static, not a payload DLL")

    print("Windows CMake product target policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
