#!/usr/bin/env python3
"""Lock the default-off cross-endpoint account-block activation contract."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(path: str, markers: tuple[str, ...]) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    normalized = " ".join(text.split())
    for marker in markers:
        if marker not in text and " ".join(marker.split()) not in normalized:
            raise AssertionError(f"{path} omits account-block activation marker: {marker}")


def main() -> int:
    require("docs/deployment/ACCOUNT_BLOCKING_ACTIVATION.md", (
        "CHATROOM_GATEWAY_ACCOUNT_BLOCKING_ENABLED=true",
        "VITE_CHAT_V2_ACCOUNT_BLOCKING=true",
        "CHATROOM_ENABLE_WINDOWS_V2_ACCOUNT_BLOCKING=ON",
        "Gateway-first activation",
        "Rollback clients first",
        "Existing negotiated connections may retain capability 7",
        "type 134",
        "type 135",
        "row-level unblock",
        "never as permission truth",
    ))
    require("docs/deployment/WEB_V2_PREVIEW.md", (
        "ACCOUNT_BLOCKING_ACTIVATION.md",
        "CHATROOM_GATEWAY_ACCOUNT_BLOCKING_ENABLED=true",
    ))
    require("docs/deployment/JAVA_GATEWAY_CONFIGURATION.md", (
        "ACCOUNT_BLOCKING_ACTIVATION.md",
        "CHATROOM_GATEWAY_ACCOUNT_BLOCKING_ENABLED=true",
    ))
    require(".github/workflows/m0-baseline.yml", (
        "Verify account-blocking Web preview candidate compiles",
        'VITE_CHAT_V2_ACCOUNT_BLOCKING: "true"',
        "web-account-blocking-gate",
        "Verify account-blocking Web candidate in Chromium and Firefox",
        'CHATROOM_V2_BROWSER_ACCOUNT_BLOCKING: "true"',
        "Verify account-blocking Web rollback candidate",
        'CHATROOM_V2_BROWSER_ACCOUNT_BLOCKING_ROLLBACK: "true"',
    ))
    require(".github/workflows/m0-product-builds.yml", (
        "-DCHATROOM_ENABLE_WINDOWS_V2_ACCOUNT_BLOCKING=ON",
        "V2WindowsAccountBlockDialogTest",
        "V2WindowsAccountBlockDirectoryDialogTest",
        "account_block_directory_dialog",
        "accountBlockingEnabled -ne $true",
    ))
    print("Account blocking activation policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
