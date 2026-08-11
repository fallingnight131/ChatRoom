#!/usr/bin/env python3
"""Lock upgraded Qt composer attachment paths to the HTTP session flow."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "Client" / "ChatWindow.cpp").read_text(encoding="utf-8")


def between(start: str, end: str) -> str:
    return SOURCE.split(start, 1)[1].split(end, 1)[0]


def main() -> int:
    sections = {
        "room file": between("void ChatWindow::onSendFile()", "void ChatWindow::onSendImage()"),
        "room image": between("void ChatWindow::onSendImage()", "void ChatWindow::onFileNotify("),
        "friend file": between("void ChatWindow::sendFriendFile(", "void ChatWindow::onFriendRecallResponse("),
    }
    for label, section in sections.items():
        if 'fileData' in section or 'FILE_SEND' in section or 'FRIEND_FILE_SEND' in section:
            print(f"[QtAttachmentSourceTest] FAIL: {label} still emits inline attachment bytes")
            return 1
    if "uploadRawFile" not in SOURCE or "httpUploadPath" not in SOURCE:
        print("[QtAttachmentSourceTest] FAIL: Qt HTTP upload negotiation is absent")
        return 1
    print("[QtAttachmentSourceTest] PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
