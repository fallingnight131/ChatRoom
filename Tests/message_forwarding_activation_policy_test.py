#!/usr/bin/env python3
"""Lock the default-off cross-endpoint forwarding activation contract."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(path: str, markers: tuple[str, ...]) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    normalized = " ".join(text.split())
    for marker in markers:
        if marker not in text and " ".join(marker.split()) not in normalized:
            raise AssertionError(f"{path} omits forwarding activation marker: {marker}")


def main() -> int:
    require("docs/deployment/JAVA_GATEWAY_CONFIGURATION.md", (
        "CHATROOM_GATEWAY_MESSAGE_FORWARDING_ENABLED",
        "CHATROOM_GATEWAY_FORWARD_WINDOW_SECONDS",
        "CHATROOM_GATEWAY_FORWARD_ATTEMPTS",
        "CHATROOM_GATEWAY_FORWARD_MAX_KEYS",
        "MESSAGE_FORWARDING_ACTIVATION.md",
    ))
    require("docs/deployment/MESSAGE_FORWARDING_ACTIVATION.md", (
        "VITE_CHAT_V2_MESSAGE_FORWARDING=true",
        "CHATROOM_ENABLE_WINDOWS_V2_FORWARDING=ON",
        "gateway-first activation",
        "client rollback happens first",
        "Apply and verify PostgreSQL migration V049",
        "Existing connections do not renegotiate",
    ))
    require("docs/deployment/WEB_V2_PREVIEW.md", (
        "CHATROOM_GATEWAY_MESSAGE_FORWARDING_ENABLED=true",
        "gateway-first activation and client-first rollback",
    ))
    require("docs/architecture/README.md", (
        "CHATROOM_GATEWAY_MESSAGE_FORWARDING_ENABLED=true",
        "VITE_CHAT_V2_MESSAGE_FORWARDING=true",
        "CHATROOM_ENABLE_WINDOWS_V2_FORWARDING=ON",
        "MESSAGE_FORWARDING_ACTIVATION.md",
        "native Windows Release build and interaction gate remain open",
    ))
    require("Backend/im-gateway/src/main/java/com/fallingnight/chat/gateway/runtime/GatewayRuntimeConfig.java", (
        '"CHATROOM_GATEWAY_MESSAGE_FORWARDING_ENABLED", false',
        '"CHATROOM_GATEWAY_FORWARD_WINDOW_SECONDS", 60, 1, 3600',
        '"CHATROOM_GATEWAY_FORWARD_ATTEMPTS", 120, 1, 10_000',
        '"CHATROOM_GATEWAY_FORWARD_MAX_KEYS",\n                        10_000, 16, 1_000_000',
    ))
    require("WebClient/src/application/v2Runtime.ts", (
        "VITE_CHAT_V2_MESSAGE_FORWARDING",
        'forwardingFlag === true || forwardingFlag === "true"',
    ))
    require("CMakeLists.txt", (
        'option(CHATROOM_ENABLE_WINDOWS_V2_FORWARDING',
        "CHAT_WINDOWS_V2_FORWARDING_ENABLED=1",
    ))
    print("Message forwarding activation policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
