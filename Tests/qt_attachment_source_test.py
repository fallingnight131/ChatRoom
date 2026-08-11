#!/usr/bin/env python3
"""Lock upgraded Qt composer attachment paths to the HTTP session flow."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "Client" / "ChatWindow.cpp").read_text(encoding="utf-8")
OUTBOX = (ROOT / "Client" / "AttachmentOutboxService.cpp").read_text(encoding="utf-8")


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
    if "downloadRawFile(fileId)" not in SOURCE or "m_httpDownloads" not in SOURCE:
        print("[QtAttachmentSourceTest] FAIL: Qt attachments do not prefer HTTP download")
        return 1
    if "FILE_UPLOAD_END_RSP" not in (ROOT / "Common" / "Protocol.h").read_text(encoding="utf-8"):
        print("[QtAttachmentSourceTest] FAIL: attachment finalization ACK type is absent")
        return 1
    if "QUuid::createUuid()" not in OUTBOX or "stageAttachment(" not in SOURCE:
        print("[QtAttachmentSourceTest] FAIL: durable Qt upload does not allocate a clientMessageId")
        return 1
    if 'endData["clientMessageId"] = m_upload.clientMessageId' not in SOURCE:
        print("[QtAttachmentSourceTest] FAIL: Qt finalization omits clientMessageId")
        return 1
    if "showPendingAttachments" not in SOURCE or "replaceSource(" not in SOURCE:
        print("[QtAttachmentSourceTest] FAIL: Qt attachment recovery controls are absent")
        return 1
    forwarding = between("// 转发消息/文件", "// 管理员：删除此消息")
    if "fileData" in forwarding or "FILE_SEND" in forwarding or "FRIEND_FILE_SEND" in forwarding:
        print("[QtAttachmentSourceTest] FAIL: Qt forwarding still emits inline attachment bytes")
        return 1
    if "FILE_FORWARD_REQ" not in forwarding or "sourceFileId" not in forwarding:
        print("[QtAttachmentSourceTest] FAIL: Qt forwarding does not use server-side file identity")
        return 1
    print("[QtAttachmentSourceTest] PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
