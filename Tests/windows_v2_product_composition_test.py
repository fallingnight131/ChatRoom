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
    block_controller = (ROOT / "Client/WindowsAccountBlockController.cpp").read_text(
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
    profile = (ROOT / "Client/ProfileDialog.cpp").read_text(encoding="utf-8")
    room_settings = (ROOT / "Client/RoomSettingsDialog.cpp").read_text(
        encoding="utf-8"
    )
    room_files = (ROOT / "Client/RoomFileManagerDialog.cpp").read_text(
        encoding="utf-8"
    )
    room_password = (ROOT / "Client/RoomPasswordPromptDialog.cpp").read_text(
        encoding="utf-8"
    )
    device_dialog = (ROOT / "Client/DeviceManagementDialog.cpp").read_text(
        encoding="utf-8"
    )
    notification_policy = (ROOT / "Client/WindowsMessageNotificationPolicy.cpp").read_text(
        encoding="utf-8"
    )
    notification_presenter = (
        ROOT / "Client/WindowsMessageNotificationPresenter.cpp"
    ).read_text(encoding="utf-8")
    tray = (ROOT / "Client/TrayManager.cpp").read_text(encoding="utf-8")
    connection_status = (
        ROOT / "Client/WindowsConnectionStatusViewModel.cpp"
    ).read_text(encoding="utf-8")
    bandwidth_policy = (ROOT / "Client/WindowsBandwidthPolicy.cpp").read_text(
        encoding="utf-8"
    )
    conversation_dialog = (ROOT / "Client/V2WindowsConversationDialog.cpp").read_text(
        encoding="utf-8"
    )
    block_directory_dialog = (
        ROOT / "Client/V2WindowsAccountBlockDirectoryDialog.cpp"
    ).read_text(encoding="utf-8")
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
        "WindowsLocalePreferenceRepository localeRepository",
        "WindowsLocaleViewModel localeViewModel",
        "LoginDialog(nullptr, &localeViewModel)",
        "ChatWindow(nullptr, &localeViewModel)",
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
    require(login, (
        "QByteArray LoginDialog::takePasswordUtf8()",
        "m_loginPass->clear()",
        "WindowsLocaleCatalog::messages(m_locale)",
        "WindowsLocaleViewModel::changed",
        "m_localeViewModel->select",
        "m_loginStatusKind",
        "m_registerStatusKind",
    ),
            "Client/LoginDialog.cpp")
    require(window, (
        "m_deviceManagementAction = m_settingsMenu->addAction(",
        "m_deviceManagementAction->setVisible(false)",
        "m_deviceManagementController->start()",
        "m_deviceManagementController->stop()",
        "DeviceManagementDialog",
        "m_v2MessageForwardingEnabled,",
        "messageSearchViewModel(),",
        "WindowsMessageNotificationPresenter",
        "accountBlockViewModel()",
        "accountBlockDirectoryViewModel()",
        "V2WindowsAccountBlockDirectoryDialog",
        "TrayManager::notificationActivated",
        "WindowsLocalePreferenceRepository",
        "WindowsLocaleViewModel",
        "m_windowsLocaleViewModel->locale()",
        "WindowsBandwidthPreferenceRepository",
        "WindowsBandwidthViewModel",
        "WindowsAvatarRequestCoordinator",
        "requestAvatar(m_username, true)",
        "new EmojiPicker(this, m_windowsLocaleViewModel)",
        "roomTargets, friendTargets, this, m_windowsLocaleViewModel",
        "username, displayName, avatar, role, this, m_windowsLocaleViewModel",
        "AvatarCropDialog dlg(img, this, m_windowsLocaleViewModel)",
        "maxMembers, this, m_windowsLocaleViewModel",
        "this, m_windowsLocaleViewModel);",
        "new RoomPasswordPromptDialog(",
        "m_roomPasswordPromptDialog->close()",
        "if (m_roomPasswordPromptDialog == dialog)",
        "copy.roomPasswordSetStatus",
        "copy.roomPasswordStatusFailedTitle, error",
        "m_deviceManagementController->viewModel(), this,",
        "m_windowsLocaleViewModel);",
        "}, 256, m_windowsLocaleViewModel);",
        "new TrayManager(this, m_windowsLocaleViewModel)",
        "WindowsLocaleViewModel::changed,",
        "&ChatWindow::refreshWindowChrome",
        "copy.mainWindowTitleForUser.arg(m_displayName)",
        "m_deviceManagementAction->setText(copy.mainMenuDevices)",
        "m_v2ConversationAction->setText(copy.mainMenuV2Preview)",
        "m_accountBlockDirectoryAction->setText(copy.mainMenuBlockedAccounts)",
        "QMessageBox::about(this, copy.mainAboutTitle, copy.mainAboutBody)",
        "m_connectionStatusViewModel->setConnected()",
        "m_connectionStatusViewModel->setDisconnected()",
        "m_connectionStatusViewModel->setReconnecting(attempt)",
        "statusBar()->addWidget(m_statusLabel, 1)",
        "statusBar()->addPermanentWidget(m_connectionStatusLabel)",
        "copy.mainConnectionStatusAccessible",
        "copy.mainReconnecting.arg(",
        "&ChatWindow::refreshComposerText",
        "copy.mainComposerEmojiAccessible",
        "copy.mainComposerFileAccessible",
        "copy.mainComposerInputAccessible",
        "copy.mainComposerSendAccessible",
        ").mainComposerInsertLineBreak",
        "&ChatWindow::refreshNavigationText",
        "copy.mainNavigationRoomsAccessible",
        "copy.mainNavigationFriendListAccessible",
        "copy.mainNavigationFriendOnline.arg(identity)",
        "copy.mainNavigationFriendOfflineAccessible.arg(identity)",
        "item->setData(Qt::UserRole + 3, isOnline)",
        "generateDefaultAvatar(identity, qHash(username))",
        "m_avatarPreview->pixmap().isNull()",
        "&ChatWindow::refreshConversationShellText",
        "copy.mainConversationDirectTitle.arg(identity)",
        "copy.mainConversationAdminTitle.arg(item->text())",
        "copy.mainConversationMemberOfflineAccessible.arg(displayName)",
        "copy.mainTrayMinimizedTitle, copy.mainTrayMinimizedBody",
    ), "Client/ChatWindow.cpp")
    composer_surface = window[
        window.index("// 工具栏"):
        window.index("// --- 右侧：用户列表 ---")
    ]
    if any(text in composer_surface for text in (
        'new QPushButton("表情")', 'new QPushButton("文件")',
        'new QPushButton("发送")', "输入消息...", 'addAction("插入换行"',
    )):
        raise AssertionError("ChatWindow composer must not embed localized copy")
    composer_projection = window[
        window.index("void ChatWindow::refreshComposerText()"):
        window.index("void ChatWindow::showAboutDialog()")
    ]
    if any(marker in composer_projection for marker in (
        "m_inputEdit->clear()", "m_inputEdit->setPlainText(",
        "m_inputEdit->moveCursor(",
    )):
        raise AssertionError("locale projection must not mutate composer draft state")
    navigation_setup = window[
        window.index("// --- 左侧：房间列表 ---"):
        window.index("// --- 中间：消息区域 ---")
    ]
    if any(text in navigation_setup for text in (
        'new QPushButton("房间")', 'new QPushButton("好友")',
        'new QPushButton("创建")', 'new QPushButton("搜索好友")',
        'new QPushButton("好友申请")',
    )):
        raise AssertionError("ChatWindow navigation must not embed localized controls")
    if 'label += " [在线]"' in window or '+ " [在线]"' in window:
        raise AssertionError("friend online state must not be stored in display text")
    navigation_projection = window[
        window.index("void ChatWindow::refreshNavigationText()"):
        window.index("void ChatWindow::showAboutDialog()")
    ]
    if any(marker in navigation_projection for marker in (
        "m_roomList->clear()", "m_friendList->clear()",
        "m_roomList->setCurrentRow(", "m_friendList->setCurrentRow(",
    )):
        raise AssertionError("locale projection must not rebuild navigation identity")
    conversation_projection = window[
        window.index("void ChatWindow::refreshConversationShellText()"):
        window.index("void ChatWindow::showAboutDialog()")
    ]
    if any(marker in conversation_projection for marker in (
        "m_messageView->setModel(", "m_userList->clear()",
        "m_currentRoomId =", "m_currentFriendUsername.clear()",
    )):
        raise AssertionError("locale projection must not mutate conversation identity")
    conversation_setup = window[
        window.index("// --- 中间：消息区域 ---"):
        window.index("// 组装")
    ]
    if any(text in conversation_setup for text in (
        'new QLabel("请选择一个窗口")',
        'setToolTip("房间设置")', 'new QLabel("聊天室成员")',
    )):
        raise AssertionError("conversation shell must not embed localized copy")
    if 'new QLabel(isOnline ? "在线" : "离线")' in window:
        raise AssertionError("member status presentation must come from the catalog")
    connection_surface = window[
        window.index("// ==================== 连接状态"):
        window.index("// ==================== 窗口事件")
    ]
    if "m_statusLabel->setText" in connection_surface or any(
        text in connection_surface for text in ("已连接", "已断开", "重连中")
    ):
        raise AssertionError(
            "connection lifecycle must not overwrite activity or own localized copy"
        )
    close_surface = window[
        window.index("void ChatWindow::closeEvent(QCloseEvent *event)"):
        window.index("void ChatWindow::moveEvent(QMoveEvent *event)")
    ]
    if 'showNotification("Qt聊天室"' in close_surface:
        raise AssertionError("minimize-to-tray notification must use the locale catalog")
    require(connection_status, (
        "State::Disconnected",
        "State::Connected",
        "State::Reconnecting",
        "std::max(1, attempt)",
        "emit changed()",
    ), "Client/WindowsConnectionStatusViewModel.cpp")
    menu_surface = window[
        window.index("void ChatWindow::setupMenuBar()"):
        window.index("// ==================== 信号连接")
    ]
    if any(text in menu_surface for text in (
        "文件(&F)", "视图(&V)", "设置(&S)", "帮助(&H)",
        "登录设备(&D)...", "检查更新(&U)...",
    )):
        raise AssertionError("ChatWindow menu surface must not embed localized copy")
    require(tray, (
        "WindowsLocaleViewModel::changed",
        "WindowsLocaleCatalog::messages(locale)",
        "copy.trayApplicationName",
        "copy.trayShowMainWindow",
        "copy.trayQuit",
        "m_showAction->setText",
        "m_quitAction->setText",
    ), "Client/TrayManager.cpp")
    if any(text in tray for text in ("Qt聊天室", "显示主窗口", "退出")):
        raise AssertionError("TrayManager must not own localized presentation copy")
    require(profile, (
        "WindowsLocaleCatalog::messages(m_locale)",
        "WindowsLocaleViewModel::changed",
        "m_localeSelector->addItem",
        "m_localeViewModel->select",
        "copy.profileLowBandwidth",
        "copy.profileChangePassword",
        "m_bandwidthViewModel->enabled()",
        "WindowsBandwidthViewModel::select",
        "m_bandwidthViewModel->saveFailed()",
    ), "Client/ProfileDialog.cpp")
    require(room_settings, (
        "WindowsLocaleCatalog::messages(m_locale)",
        "WindowsLocaleViewModel::changed",
        "m_passwordEdit->setEchoMode(QLineEdit::Password)",
        "m_developerKeyEdit->clear()",
        "m_passwordEdit->clear()",
        "AvatarCropDialog dlg(img, this, m_localeViewModel)",
    ), "Client/RoomSettingsDialog.cpp")
    require(room_files, (
        "WindowsLocaleCatalog::messages(m_locale)",
        "WindowsLocaleViewModel::changed",
        "updateLocalizedRows()",
        'statusItem->setData(Qt::UserRole, cleared)',
        "check->property(\"fileId\").toInt()",
    ), "Client/RoomFileManagerDialog.cpp")
    require(room_password, (
        "WindowsLocaleCatalog::messages(m_locale)",
        "WindowsLocaleViewModel::changed",
        "m_passwordEdit->setEchoMode(QLineEdit::Password)",
        "if (password.isEmpty())",
        "m_passwordEdit->clear()",
        "emit joinRequested(m_roomId, password)",
    ), "Client/RoomPasswordPromptDialog.cpp")
    require(device_dialog, (
        "WindowsLocaleCatalog::messages(m_locale)",
        "WindowsLocaleViewModel::changed",
        "DeviceManagementViewModel::Failure::InvalidDirectory",
        "item->setData(Qt::UserRole, device.deviceId)",
        "description->setTextFormat(Qt::PlainText)",
        "confirmation.setDefaultButton(cancel)",
        "confirmation.clickedButton() == confirm",
        "m_viewModel->revoke(deviceId)",
    ), "Client/DeviceManagementDialog.cpp")
    require(notification_policy, (
        "Kind::Mention : Kind::GenericMessage",
        "decision.conversationId = message.conversationId",
    ), "Client/WindowsMessageNotificationPolicy.cpp")
    for forbidden in ("有人提到了你", "新消息", "打开聊天软件查看消息"):
        if forbidden in notification_policy:
            raise AssertionError("notification policy must retain semantics, not catalog copy")
    require(notification_presenter, (
        "m_localeViewModel->locale()",
        "copy.notificationMentionedYou",
        "copy.notificationNewMessage",
        "copy.notificationOpenApp",
    ), "Client/WindowsMessageNotificationPresenter.cpp")
    require(bandwidth_policy, (
        "WindowsBandwidthPolicy::shouldAutoRequestAvatar",
        "!lowBandwidthEnabled && !cached",
        "if (!explicitRequest",
        "m_dispatch(accountId)",
    ), "Client/WindowsBandwidthPolicy.cpp")
    for source, text in (
        ("Client/WindowsV2MessagingController.cpp", messaging_controller),
        ("Client/V2WindowsMessagingPanel.cpp", panel),
    ):
        if "lowBandwidth" in text:
            raise AssertionError(
                f"{source} must not weaken messaging for low-bandwidth mode"
            )
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
    require(block_controller, (
        "m_directoryViewModel->applyFailure(event.retryable)",
        "m_viewModel->applyFailure(operationId, event.retryable)",
    ), "Client/WindowsAccountBlockController.cpp")
    if any(text in block_controller for text in (
        "屏蔽目录暂不可用", "无法读取屏蔽目录",
    )):
        raise AssertionError(
            "WindowsAccountBlockController must not manufacture localized server detail"
        )
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
        "copy.composerBudgetAccessible",
        "copy.copyMessageAccessible",
        "QGuiApplication::clipboard()",
        "m_mentionsEnabled && !m_conversationId.isEmpty()",
        "V2WindowsMentionComposer::serialize",
        'setProperty("mentionTargetAccountIds"',
        "copy.searchInputAccessible",
        "V2WindowsMessageSearchViewModel::loadMore",
        "scrollToItem(item, QAbstractItemView::PositionAtCenter)",
    ), "Client/V2WindowsMessagingPanel.cpp")
    require(conversation_dialog, (
        "V2WindowsAccountBlockDialog",
        "m_selectedConversationDirect",
        "m_accountBlock->setEnabled",
        "m_localeViewModel->select(locale)",
        "m_messagingPanel->setLocale(m_locale)",
    ), "Client/V2WindowsConversationDialog.cpp")
    require(block_directory_dialog, (
        "requestUnblock",
        "WindowsLocaleCatalog::messages(m_locale)",
        "WindowsLocaleViewModel::changed",
        "failureDetail().isEmpty()",
        "confirmation.setDefaultButton(cancel)",
    ), "Client/V2WindowsAccountBlockDirectoryDialog.cpp")
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
