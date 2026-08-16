#!/usr/bin/env python3
"""Lock the default-off cross-endpoint message-search release contract."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(path: str, markers: tuple[str, ...]) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    normalized = " ".join(text.split())
    for marker in markers:
        if marker not in text and " ".join(marker.split()) not in normalized:
            raise AssertionError(f"{path} omits search activation marker: {marker}")


def main() -> int:
    require("docs/deployment/MESSAGE_SEARCH_ACTIVATION.md", (
        "CHATROOM_GATEWAY_MESSAGE_SEARCH_ENABLED=true",
        "VITE_CHAT_V2_MESSAGE_SEARCH=true",
        "CHATROOM_ENABLE_WINDOWS_V2_SEARCH=ON",
        "gateway-first activation",
        "client-first rollback",
        "Existing connections do not renegotiate",
        "never persists search results or partial context windows",
    ))
    require("docs/deployment/WEB_V2_PREVIEW.md", (
        "MESSAGE_SEARCH_ACTIVATION.md",
        "CHATROOM_GATEWAY_MESSAGE_SEARCH_ENABLED=true",
    ))
    require("docs/deployment/JAVA_GATEWAY_CONFIGURATION.md", (
        "MESSAGE_SEARCH_ACTIVATION.md",
        "CHATROOM_GATEWAY_MESSAGE_SEARCH_ENABLED=true",
    ))
    require(".github/workflows/m0-baseline.yml", (
        "Verify search-enabled Web preview candidate compiles",
        'VITE_CHAT_V2_MESSAGE_SEARCH: "true"',
        "build/m6/web-search-gate",
    ))
    require(".github/workflows/m0-product-builds.yml", (
        "-DCHATROOM_ENABLE_WINDOWS_V2_SEARCH=ON",
        "V2WindowsMessageSearchViewModelTest",
        "WindowsV2MessagingControllerTest",
        "V2WindowsMessagingPanelTest",
        "messageSearchEnabled -ne $true",
    ))
    require("CMakeLists.txt", (
        "V2WindowsMessageSearchViewModelTest",
        "WindowsV2MessagingControllerTest",
        "V2WindowsMessagingPanelTest",
        "m6_windows_v2_message_search_view_model",
        "m6_windows_v2_messaging_controller",
        "m6_windows_v2_messaging_panel",
    ))
    require("docs/architecture/README.md", (
        "MESSAGE_SEARCH_ACTIVATION.md",
        "native Windows Release and endpoint canary evidence remain open",
    ))
    print("Message search activation policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
