#!/usr/bin/env python3
"""Lock the default-off Windows V2 device-management product composition."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(text: str, markers: tuple[str, ...], source: str) -> None:
    for marker in markers:
        if marker not in text:
            raise AssertionError(f"{source} omits Windows V2 composition marker: {marker}")


def main() -> int:
    cmake = (ROOT / "CMakeLists.txt").read_text(encoding="utf-8")
    main_source = (ROOT / "Client/main.cpp").read_text(encoding="utf-8")
    login = (ROOT / "Client/LoginDialog.cpp").read_text(encoding="utf-8")
    window = (ROOT / "Client/ChatWindow.cpp").read_text(encoding="utf-8")
    controller = (ROOT / "Client/WindowsDeviceManagementController.cpp").read_text(
        encoding="utf-8"
    )
    messaging_controller = (ROOT / "Client/WindowsV2MessagingController.cpp").read_text(
        encoding="utf-8"
    )
    session_protocol = (ROOT / "Client/V2WindowsSessionProtocolClient.cpp").read_text(
        encoding="utf-8"
    )
    v2_transport = (ROOT / "Client/V2WindowsDeviceManagementTransport.cpp").read_text(
        encoding="utf-8"
    )
    panel = (ROOT / "Client/V2WindowsMessagingPanel.cpp").read_text(encoding="utf-8")
    conversation_dialog = (ROOT / "Client/V2WindowsConversationDialog.cpp").read_text(
        encoding="utf-8"
    )
    qmake = (ROOT / "Client/Client.pro").read_text(encoding="utf-8")

    for source, text in (
        ("CMakeLists.txt", cmake),
        ("Client/ChatWindow.cpp", window),
        ("Client/WindowsV2MessagingController.cpp", messaging_controller),
        ("Client/V2WindowsMessagingPanel.cpp", panel),
        ("Client/V2WindowsDeviceManagementTransport.cpp", v2_transport),
        ("Client/Client.pro", qmake),
    ):
        if "V2WindowsAttachmentProtocolClient" in text:
            raise AssertionError(
                f"{source} must not activate Windows V2 attachments before provider gates"
            )

    require(cmake, (
        "CHAT_WINDOWS_V2_PRODUCT_AVAILABLE=1",
        "Client/WindowsDeviceManagementController.cpp",
        "chatroom_windows_v2_transport",
    ), "CMakeLists.txt")
    require(main_source, (
        "WindowsV2ProductConfiguration::fromBuild()",
        "WindowsDeviceIdentityRepository",
        "takePasswordUtf8()",
        "configureDeviceManagement(",
        "v2Configuration.messageForwardingEnabled",
        "v2Configuration.messageSearchEnabled",
        "v2Configuration.notificationsEnabled",
        "v2Configuration.accountBlockingEnabled",
        "password.fill('\\0')",
    ), "Client/main.cpp")
    require(login, ("QByteArray LoginDialog::takePasswordUtf8()", "m_loginPass->clear()"),
            "Client/LoginDialog.cpp")
    require(window, (
        'QStringLiteral("登录设备(&D)...")',
        "m_deviceManagementAction->setVisible(false)",
        "m_deviceManagementController->start()",
        "m_deviceManagementController->stop()",
        "DeviceManagementDialog",
        "m_v2MessageForwardingEnabled,",
        "messageSearchViewModel(),",
        "WindowsMessageNotificationPresenter",
        "accountBlockViewModel()",
        "TrayManager::notificationActivated",
    ), "Client/ChatWindow.cpp")
    require(controller, (
        "ReadyForAuthentication",
        "authenticationRejected",
        "DeviceManagementViewModel::applyDirectory",
        "DeviceManagementViewModel::applyRevoked",
        "DeviceManagementViewModel::applyProtocolError",
        "enableMessageForwarding",
        "enableMessageSearch",
        "enableAccountBlocking",
        "WindowsAccountBlockController",
        "accountBlockViewModel",
    ), "Client/WindowsDeviceManagementController.cpp")
    require(session_protocol, (
        "CLIENT_CAPABILITY_MESSAGE_MENTIONS",
        "CLIENT_CAPABILITY_MESSAGE_SEARCH",
        "CLIENT_CAPABILITY_ACCOUNT_BLOCKING",
    ), "Client/V2WindowsSessionProtocolClient.cpp")
    require(messaging_controller, (
        "participant.accountId) == m_accountId",
        "m_participantProtocol->abandon(command.requestId)",
        "m_participantViewModel->refresh()",
        "m_service->stageText(",
        "m_service->saveDraft(",
        "configureForwarding(",
        "V2WindowsMessageSearchProtocolClient",
        "requestSearch(",
    ), "Client/WindowsV2MessagingController.cpp")
    require(panel, (
        "m_viewModel->sendText(",
        "m_draftSaveTimer->setInterval(400)",
        "m_viewModel->persistDraft(",
        "key->modifiers() == Qt::ControlModifier",
        "key->key() == Qt::Key_Escape",
        "V2LocalMessageRepository::MaxTextBytes",
        'setAccessibleName(QStringLiteral("消息字节数"))',
        'setAccessibleName(QStringLiteral("复制此消息正文"))',
        "QGuiApplication::clipboard()",
        "m_mentionsEnabled && !m_conversationId.isEmpty()",
        "V2WindowsMentionComposer::serialize",
        'setProperty("mentionTargetAccountIds"',
        'setAccessibleName(QStringLiteral("搜索当前会话消息"))',
        "V2WindowsMessageSearchViewModel::loadMore",
        "scrollToItem(item, QAbstractItemView::PositionAtCenter)",
    ), "Client/V2WindowsMessagingPanel.cpp")
    require(conversation_dialog, (
        "V2WindowsAccountBlockDialog",
        "m_selectedConversationDirect",
        "m_accountBlock->setEnabled",
    ), "Client/V2WindowsConversationDialog.cpp")
    for source in (
        "Client/WindowsDeviceManagementController.cpp",
        "Client/DeviceManagementApplicationService.cpp",
        "Client/WindowsDeviceIdentityRepository.cpp",
    ):
        if "QSettings" in (ROOT / source).read_text(encoding="utf-8"):
            raise AssertionError(f"{source} must not persist V2 security state in QSettings")
    if "WindowsDeviceManagementController" in qmake or "protobuf" in qmake.lower():
        raise AssertionError("qmake rollback must not activate the canonical CMake V2 stack")

    print("Windows V2 product composition policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
